/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

#![allow(missing_docs, missing_debug_implementations)]

use aws_smithy_http::operation::Operation;
use aws_smithy_http::result::{SdkError, SdkSuccess};
use aws_smithy_http::retry::{ClassifyRetry, DefaultResponseRetryClassifier};
use aws_smithy_types::retry::ProvideErrorKind;
use std::future::Future;
use std::marker::PhantomData;
use std::pin::Pin;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;
use std::task::{Context, Poll};
use tokio::sync::{OwnedSemaphorePermit, Semaphore};
use tower::{Layer, Service};
use tracing::{error, info, warn};

static LAST_COUNTER_ID: AtomicUsize = AtomicUsize::new(1);

pub type TokenBucket = Arc<Semaphore>;

pub struct CounterService<S> {
    id: usize,
    inner: S,
    counter: usize,
    token_bucket: TokenBucket,
}

impl<S> Clone for CounterService<S>
where
    S: Clone,
{
    fn clone(&self) -> Self {
        let id = LAST_COUNTER_ID.fetch_add(1, Ordering::SeqCst);
        warn!(
            "cloning bucket with {} available tokens",
            self.token_bucket.available_permits()
        );

        Self {
            id,
            inner: self.inner.clone(),
            counter: self.counter,
            token_bucket: self.token_bucket.clone(),
        }
    }
}

impl<S> CounterService<S> {
    pub fn new(service: S, token_bucket: TokenBucket) -> Self {
        Self {
            id: LAST_COUNTER_ID.load(Ordering::SeqCst),
            inner: service,
            counter: 0,
            token_bucket,
        }
    }

    pub fn count(&self) -> usize {
        self.counter
    }

    pub fn increment(&mut self) {
        self.counter += 1;
        info!("counter #{} req #{}", self.id, self.counter);
    }
}

impl<S, H, R, T, E> Service<Operation<H, R>> for CounterService<S>
where
    S: Service<Operation<H, R>, Response = SdkSuccess<T>, Error = SdkError<E>>,
    S::Future: 'static,
    E: std::error::Error + 'static,
    H: 'static,
    R: ClassifyRetry<S::Response, S::Error> + 'static,
{
    type Response = S::Response;
    type Error = S::Error;
    type Future = Pin<Box<dyn Future<Output = Result<S::Response, S::Error>>>>;

    fn poll_ready(&mut self, cx: &mut Context<'_>) -> Poll<Result<(), Self::Error>> {
        self.inner.poll_ready(cx)
    }

    fn call(&mut self, req: Operation<H, R>) -> Self::Future {
        self.increment();
        let token_bucket = self.token_bucket.clone();
        info!(
            "taking 5 tokens from bucket ({} - 5)",
            token_bucket.available_permits()
        );
        let token: OwnedSemaphorePermit = token_bucket
            .clone()
            .try_acquire_many_owned(5)
            .expect("bucket has available tokens");
        let classifier = req.retry_classifier().clone();
        let fut = self.inner.call(req);

        Box::pin(async move {
            let mut res = fut.await;
            let retry_kind = classifier.classify_retry(res.as_ref());

            match &mut res {
                Ok(success) => {
                    drop(token);
                    success.raw.properties_mut().insert(retry_kind);
                    info!("success");
                }
                Err(SdkError::ServiceError(failure)) => {
                    token.forget();
                    failure.raw.properties_mut().insert(retry_kind);
                    error!("{}", failure.err());
                }
                _ => todo!("handle this case"),
            };

            info!("tokens in bucket = {}", token_bucket.available_permits());

            res
        })
    }
}

pub struct CounterLayer<S> {
    token_bucket: TokenBucket,
    inner: PhantomData<S>,
}

impl<S> CounterLayer<S> {
    pub fn new(token_bucket: TokenBucket) -> Self {
        error!("creating new counter layer");
        Self {
            token_bucket,
            inner: PhantomData,
        }
    }
}

impl<S> Layer<S> for CounterLayer<S> {
    type Service = CounterService<S>;

    fn layer(&self, inner: S) -> Self::Service {
        warn!("creating new counter service");
        CounterService::new(inner, self.token_bucket.clone())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tower::layer::util::Identity;

    #[tokio::test]
    async fn test_counter_service() {
        let request = ();
        let response_handler = ();
        let op = Operation::new(request, response_handler);
        let token_bucket = Arc::new(tokio::sync::Semaphore::new(500));
        let svc = CounterService::new(Identity::new(), token_bucket);

        let res = svc.call(op).await.unwrap();
    }
}
