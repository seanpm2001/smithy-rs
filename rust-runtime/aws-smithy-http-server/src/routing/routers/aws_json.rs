/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

use std::{
    convert::Infallible,
    task::{Context, Poll},
};

use http::{Method, Request, Response};
use tower::{Layer, Service, ServiceExt};

use crate::{
    body::BoxBody,
    protocols::Protocol,
    routing::{future::RouterFuture2, tiny_map::TinyMap},
};

// This constant determines when the `TinyMap` implementation switches from being a `Vec` to a
// `HashMap`. This is chosen to be 15 as a result of the discussion around
// https://github.com/awslabs/smithy-rs/pull/1429#issuecomment-1147516546
const ROUTE_CUTOFF: usize = 15;

fn layer_routes<Routes, L, S, NewRoutes>(routes: Routes, layer: L) -> NewRoutes
where
    L: Layer<S>,
    NewRoutes: FromIterator<(String, L::Service)>,
    Routes: IntoIterator<Item = (String, S)>,
{
    routes
        .into_iter()
        .map(|(key, route)| (key, layer.layer(route)))
        .collect()
}

#[inline]
fn match_route<const CUTOFF: usize, B, S>(
    request: Request<B>,
    routes: &TinyMap<String, S, CUTOFF>,
) -> Option<RouterFuture2<S, B>>
where
    S: Service<Request<B>, Response = Response<BoxBody>> + Clone,
{
    if request.uri() != "/" {
        return None;
    }

    // The HTTP method is not POST.
    if request.method() != Method::POST {
        return Some(super::method_not_allowed());
    }

    // Find the `x-amz-target` header.
    let target = request.headers().get("x-amz-target")?;
    let target = target.to_str().ok()?;

    // Lookup in the `TinyMap` for a route for the target.
    let route = routes.get(target)?;
    Some(route.clone().oneshot(request).into())
}

#[derive(Debug, Clone)]
pub struct AwsJson10Router<S> {
    routes: TinyMap<String, S, ROUTE_CUTOFF>,
}

impl<S> AwsJson10Router<S> {
    pub fn layer<L: Layer<S>>(self, layer: L) -> AwsJson10Router<L::Service> {
        AwsJson10Router {
            routes: layer_routes(self.routes, layer),
        }
    }
}

impl<B, S> Service<Request<B>> for AwsJson10Router<S>
where
    B: Send + 'static,
    S: Service<Request<B>, Response = Response<BoxBody>, Error = Infallible> + Clone,
{
    type Response = Response<BoxBody>;
    type Error = S::Error;
    type Future = RouterFuture2<S, B>;

    #[inline]
    fn poll_ready(&mut self, _: &mut Context<'_>) -> Poll<Result<(), Self::Error>> {
        Poll::Ready(Ok(()))
    }

    #[inline]
    fn call(&mut self, request: Request<B>) -> Self::Future {
        if let Some(fut) = match_route(request, &self.routes) {
            fut
        } else {
            // In any other case return the `RuntimeError::UnknownOperation`.
            super::unknown_operation(Protocol::AwsJson10)
        }
    }
}

#[derive(Debug, Clone)]
pub struct AwsJson11Router<S> {
    routes: TinyMap<String, S, ROUTE_CUTOFF>,
}

impl<S> AwsJson11Router<S> {
    pub fn layer<L: Layer<S>>(self, layer: L) -> AwsJson11Router<L::Service> {
        AwsJson11Router {
            routes: layer_routes(self.routes, layer),
        }
    }
}

impl<B, S> Service<Request<B>> for AwsJson11Router<S>
where
    B: Send + 'static,
    S: Service<Request<B>, Response = Response<BoxBody>, Error = Infallible> + Clone,
{
    type Response = Response<BoxBody>;
    type Error = Infallible;
    type Future = RouterFuture2<S, B>;

    #[inline]
    fn poll_ready(&mut self, _: &mut Context<'_>) -> Poll<Result<(), Self::Error>> {
        Poll::Ready(Ok(()))
    }

    #[inline]
    fn call(&mut self, request: Request<B>) -> Self::Future {
        if let Some(fut) = match_route(request, &self.routes) {
            fut
        } else {
            // In any other case return the `RuntimeError::UnknownOperation`.
            super::unknown_operation(Protocol::AwsJson11)
        }
    }
}
