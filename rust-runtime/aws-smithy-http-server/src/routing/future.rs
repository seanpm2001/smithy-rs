/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

// This code was copied and then modified from Tokio's Axum.

/* Copyright (c) 2021 Tower Contributors
 *
 * Permission is hereby granted, free of charge, to any
 * person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the
 * Software without restriction, including without
 * limitation the rights to use, copy, modify, merge,
 * publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software
 * is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice
 * shall be included in all copies or substantial portions
 * of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF
 * ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED
 * TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
 * PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT
 * SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY
 * CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR
 * IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 */

//! Future types.
use crate::body::BoxBody;
use futures_util::future::Either;
use http::{Request, Response};
use std::{
    convert::Infallible,
    future::Future,
    future::{ready, Ready},
    pin::Pin,
    task::{Context, Poll},
};
use tower::{util::Oneshot, Service};

pub use super::{into_make_service::IntoMakeService, route::RouteFuture};

type OneshotRoute<B> = Oneshot<super::Route<B>, Request<B>>;
type ReadyResponse = Ready<Result<Response<BoxBody>, Infallible>>;

opaque_future! {
    /// Response future for [`Router`](super::Router).
    pub type RouterFuture<B> =
        futures_util::future::Either<OneshotRoute<B>, ReadyResponse>;
}

impl<B> RouterFuture<B> {
    pub(super) fn from_oneshot(future: Oneshot<super::Route<B>, Request<B>>) -> Self {
        Self::new(Either::Left(future))
    }

    pub(super) fn from_response(response: Response<BoxBody>) -> Self {
        Self::new(Either::Right(ready(Ok(response))))
    }
}

pin_project_lite::pin_project! {
    pub struct RouterFuture2<S, B> where S: Service<Request<B>> {
        #[pin]
        inner: Either<Oneshot<S, Request<B>>, Ready<Result<S::Response, S::Error>>>
    }
}

impl<S, B> From<Oneshot<S, Request<B>>> for RouterFuture2<S, B>
where
    S: Service<Request<B>>,
{
    fn from(value: Oneshot<S, Request<B>>) -> Self {
        Self {
            inner: Either::Left(value),
        }
    }
}

impl<S, B> From<Response<BoxBody>> for RouterFuture2<S, B>
where
    S: Service<Request<B>, Response = Response<BoxBody>>,
{
    fn from(value: Response<BoxBody>) -> Self {
        Self {
            inner: Either::Right(ready(Ok(value))),
        }
    }
}

impl<S, B> Future for RouterFuture2<S, B>
where
    S: Service<Request<B>>,
{
    type Output = <S::Future as Future>::Output;

    fn poll(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Self::Output> {
        self.project().inner.poll(cx)
    }
}
