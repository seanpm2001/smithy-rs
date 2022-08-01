/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

use std::{
    convert::Infallible,
    future::Future,
    marker::PhantomData,
    task::{Context, Poll},
};

use futures_util::{future::Map, FutureExt};
use tower::Service;

pub trait Handler<T, B> {
    type Future: Future<Output = http::Response<B>>;

    fn call(&self, request: http::Request<B>) -> Self::Future;
}

pub trait FromRequest<B> {
    fn from_request(request: http::Request<B>) -> Self;
}

pub trait IntoResponse<B> {
    fn into_response(self) -> http::Response<B>;
}

impl<T1, B, F, Fut> Handler<(T1,), B> for F
where
    F: FnOnce(T1) -> Fut + Clone,
    Fut: Future,
    T1: FromRequest<B>,
    Fut::Output: IntoResponse<B>,
{
    type Future = Map<Fut, fn(Fut::Output) -> http::Response<B>>;

    fn call(&self, request: http::Request<B>) -> Self::Future {
        let t1 = T1::from_request(request);
        (self.clone())(t1).map(<Fut::Output>::into_response)
    }
}

impl<T1, T2, B, F, Fut> Handler<(T1, T2), B> for F
where
    F: FnOnce(T1, T2) -> Fut + Clone,
    Fut: Future,
    (T1, T2): FromRequest<B>,
    Fut::Output: IntoResponse<B>,
{
    type Future = Map<Fut, fn(Fut::Output) -> http::Response<B>>;

    fn call(&self, request: http::Request<B>) -> Self::Future {
        let (t1, t2) = <(T1, T2)>::from_request(request);
        (self.clone())(t1, t2).map(<Fut::Output>::into_response)
    }
}

pub struct HandlerService<H, T> {
    handler: H,
    _args: PhantomData<T>,
}

impl<B, H, T> Service<http::Request<B>> for HandlerService<H, T>
where
    H: Handler<T, B>,
{
    type Response = http::Response<B>;

    type Error = Infallible;

    type Future = Map<H::Future, fn(http::Response<B>) -> Result<http::Response<B>, Infallible>>;

    fn poll_ready(&mut self, _cx: &mut Context<'_>) -> Poll<Result<(), Self::Error>> {
        Poll::Ready(Ok(()))
    }

    fn call(&mut self, request: http::Request<B>) -> Self::Future {
        self.handler.call(request).map(Ok)
    }
}

pub trait HandlerExt<T, B>: Handler<T, B> {
    fn into_service(self) -> HandlerService<Self, T>
    where
        Self: Sized,
    {
        HandlerService {
            handler: self,
            _args: PhantomData,
        }
    }
}
