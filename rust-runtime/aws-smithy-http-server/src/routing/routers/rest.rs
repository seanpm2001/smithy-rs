use std::{
    convert::Infallible,
    task::{Context, Poll},
};

use http::{Request, Response};
use tower::{Layer, Service, ServiceExt};

use crate::{
    body::BoxBody,
    protocols::Protocol,
    routing::{
        future::RouterFuture2,
        request_spec::{Match, RequestSpec},
    },
};

enum MethodAllowed {
    Yes,
    No,
}

fn layer_routes<Routes, L, S, NewRoutes>(routes: Routes, layer: L) -> NewRoutes
where
    L: Layer<S>,
    NewRoutes: FromIterator<(L::Service, RequestSpec)>,
    Routes: IntoIterator<Item = (S, RequestSpec)>,
{
    routes
        .into_iter()
        .map(|(route, request_spec)| (layer.layer(route), request_spec))
        .collect()
}

#[inline]
fn from_iter_then_sort<S, T: IntoIterator<Item = (S, RequestSpec)>>(iter: T) -> Vec<(S, RequestSpec)> {
    let mut routes: Vec<(S, RequestSpec)> = iter
        .into_iter()
        .map(|(svc, request_spec)| (svc, request_spec))
        .collect();

    // Sort them once by specificity, with the more specific routes sorted before the less
    // specific ones, so that when routing a request we can simply iterate through the routes
    // and pick the first one that matches.
    routes.sort_by_key(|(_route, request_spec)| std::cmp::Reverse(request_spec.rank()));

    routes
}

// Loop through all the routes and validate if any of them matches. Routes are already ranked.
#[inline]
fn match_route<B, S>(request: Request<B>, routes: &Vec<(S, RequestSpec)>) -> Result<RouterFuture2<S, B>, MethodAllowed>
where
    S: Service<Request<B>> + Clone,
{
    let mut method_allowed = MethodAllowed::Yes;

    for (route, request_spec) in routes {
        match request_spec.matches(&request) {
            Match::Yes => {
                return Ok(route.clone().oneshot(request).into());
            }
            Match::MethodNotAllowed => method_allowed = MethodAllowed::No,
            // Continue looping to see if another route matches.
            Match::No => continue,
        }
    }

    Err(method_allowed)
}

#[derive(Debug, Clone)]
pub struct RestJsonRouter<S> {
    routes: Vec<(S, RequestSpec)>,
}

impl<S> RestJsonRouter<S> {
    pub fn layer<L: Layer<S>>(self, layer: L) -> RestJsonRouter<L::Service> {
        RestJsonRouter {
            routes: layer_routes(self.routes, layer),
        }
    }
}

impl<B, S> Service<Request<B>> for RestJsonRouter<S>
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
        match match_route(request, &self.routes) {
            Ok(fut) => fut,
            // The HTTP method is not correct.
            Err(MethodAllowed::No) => super::method_not_allowed(),
            // In any other case return the `RuntimeError::UnknownOperation`.
            Err(MethodAllowed::Yes) => super::unknown_operation(Protocol::RestJson1),
        }
    }
}

impl<S> FromIterator<(S, RequestSpec)> for RestJsonRouter<S> {
    #[inline]
    fn from_iter<T: IntoIterator<Item = (S, RequestSpec)>>(iter: T) -> Self {
        Self {
            routes: from_iter_then_sort(iter),
        }
    }
}

#[derive(Debug, Clone)]
pub struct RestXmlRouter<S> {
    routes: Vec<(S, RequestSpec)>,
}

impl<S> RestXmlRouter<S> {
    pub fn layer<L: Layer<S>>(self, layer: L) -> RestJsonRouter<L::Service> {
        RestJsonRouter {
            routes: layer_routes(self.routes, layer),
        }
    }
}

impl<B, S> Service<Request<B>> for RestXmlRouter<S>
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
        match match_route(request, &self.routes) {
            Ok(fut) => fut,
            // The HTTP method is not correct.
            Err(MethodAllowed::No) => super::method_not_allowed(),
            // In any other case return the `RuntimeError::UnknownOperation`.
            Err(MethodAllowed::Yes) => super::unknown_operation(Protocol::RestXml),
        }
    }
}

impl<S> FromIterator<(S, RequestSpec)> for RestXmlRouter<S> {
    #[inline]
    fn from_iter<T: IntoIterator<Item = (S, RequestSpec)>>(iter: T) -> Self {
        Self {
            routes: from_iter_then_sort(iter),
        }
    }
}
