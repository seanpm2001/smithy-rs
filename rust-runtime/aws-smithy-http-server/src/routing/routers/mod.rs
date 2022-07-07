use http::{Request, Response, StatusCode};
use tower::{Layer, Service};

use crate::{
    body::BoxBody,
    protocols::Protocol,
    runtime_error::{RuntimeError, RuntimeErrorKind},
};

use super::{future::RouterFuture2, request_spec::RequestSpec};

mod aws_json;
mod rest;

pub use aws_json::*;
pub use rest::*;

/// Return the HTTP error response for non allowed method.
fn method_not_allowed<S, B>() -> RouterFuture2<S, B>
where
    S: Service<Request<B>, Response = Response<BoxBody>>,
{
    let mut res = Response::new(crate::body::empty());
    *res.status_mut() = StatusCode::METHOD_NOT_ALLOWED;
    res.into()
}

/// Return the correct, protocol-specific "Not Found" response for an unknown operation.
fn unknown_operation<S, B>(protocol: Protocol) -> RouterFuture2<S, B>
where
    S: Service<Request<B>, Response = Response<BoxBody>>,
{
    let error = RuntimeError {
        protocol,
        kind: RuntimeErrorKind::UnknownOperation,
    };
    error.into_response().into()
}
