/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

#![allow(missing_docs, missing_debug_implementations)]

use std::future::Future;
use std::marker::PhantomData;
use std::pin::Pin;
use std::task::{Context, Poll};
use tower::{Layer, Service};

#[derive(Clone)]
pub struct BoxingService<S>(S);

impl<S> BoxingService<S> {
    pub fn new(service: S) -> Self {
        Self(service)
    }
}

impl<S, Req> Service<Req> for BoxingService<S>
where
    S: Service<Req>,
    S::Future: 'static,
{
    type Response = S::Response;
    type Error = S::Error;
    type Future = Pin<Box<dyn Future<Output = Result<S::Response, S::Error>>>>;

    fn poll_ready(&mut self, cx: &mut Context<'_>) -> Poll<Result<(), Self::Error>> {
        self.0.poll_ready(cx)
    }

    fn call(&mut self, req: Req) -> Self::Future {
        let fut = self.0.call(req);

        Box::pin(async move { fut.await })
    }
}

pub struct BoxingLayer<S>(PhantomData<S>);

impl<S> BoxingLayer<S> {
    pub fn new() -> Self {
        Self(PhantomData)
    }
}

impl<S> Layer<S> for BoxingLayer<S> {
    type Service = BoxingService<S>;

    fn layer(&self, inner: S) -> Self::Service {
        BoxingService::new(inner)
    }
}
