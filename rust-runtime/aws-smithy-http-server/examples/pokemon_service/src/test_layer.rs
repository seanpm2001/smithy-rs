use futures::future::BoxFuture;
use http::Request;
use std::collections::HashMap;
use std::task::{Context, Poll};
use tower::Service;

use aws_sdk_kms::{model::SigningAlgorithmSpec, types::Blob, Client};
use url::Url;

use tower::Layer;

pub struct TestLayer {
    pub sdk_config: aws_types::SdkConfig,
}

impl<S> Layer<S> for TestLayer {
    type Service = TestService<S>;

    fn layer(&self, inner: S) -> Self::Service {
        TestService {
            kms_client: Client::new(&self.sdk_config),
            inner,
        }
    }
}

#[derive(Debug, Clone)]
pub struct TestService<S> {
    kms_client: Client,
    inner: S,
}

impl<S, RequestBody> Service<Request<RequestBody>> for TestService<S>
where
    // While our layer is generic over the _request_ body type, it renders _concrete_ responses
    // within it that are not coming from the wrapped service, but that we build ourselves. We
    // hence need to specify a concrete response type, and that response type should match the one
    // our wrapped service returns. Since we wrap a Smithy service that returns responses with
    // `aws_smithy_http_server::body::BoxBody` bodies, we in turn must return those too.
    S: Service<Request<RequestBody>, Response = http::Response<aws_smithy_http_server::body::BoxBody>>
        + Clone
        + Send
        + 'static,
    RequestBody: Send + 'static,
    S::Future: Send + 'static,
{
    type Response = S::Response;
    type Error = S::Error;
    // Our code uses an async closure in order to be able to `await` the KMS client, so we need to
    // return a future we cannot statically name. So we use a type-erased dynamic boxed future
    // type. The `futures` crate conveniently defines such a type. You could also define your own
    // future type if you want to do more involved things.
    type Future = BoxFuture<'static, Result<Self::Response, Self::Error>>;

    fn poll_ready(&mut self, cx: &mut Context<'_>) -> Poll<Result<(), Self::Error>> {
        self.inner.poll_ready(cx)
    }

    fn call(&mut self, request: Request<RequestBody>) -> Self::Future {
        println!("Headers: {:?}", request.headers());
        let url_string = "http://example.com".to_string() + &request.uri().to_string();
        let param_map: HashMap<String, String> = Url::parse(&url_string).unwrap().query_pairs().into_owned().collect();
        let has_signature_param = param_map.contains_key("signature");
        let mut signature_val = "".to_string();
        let mut cell_val = "".to_string();

        if has_signature_param {
            signature_val = param_map.get("signature").unwrap().to_string();
        }

        if param_map.contains_key("cell") {
            cell_val = param_map.get("cell").unwrap().to_string();
        }

        // https://docs.rs/aws-sdk-kms/latest/aws_sdk_kms/client/fluent_builders/struct.Verify.html#method.message
        let message = String::from("message to be signed");

        let verify_request = self
            .kms_client
            .verify()
            .key_id("alias/SomeSigningKey")
            .message(Blob::new(message))
            .signature(Blob::new(signature_val.to_string()))
            .signing_algorithm(SigningAlgorithmSpec::RsassaPkcs1V15Sha256);

        // Best practice is to clone the inner `Service` like this, when we need to await it in a
        // boxed future.
        // See https://docs.rs/tower-service/latest/tower_service/trait.Service.html#be-careful-when-cloning-inner-services
        // for details.
        let clone = self.inner.clone();
        let mut inner = std::mem::replace(&mut self.inner, clone);

        // Your code was using `await` directly within `call`, which is not async (async traits are
        // not supported in rustc yet). We therefore need to box and pin an async closure.
        // This issue explains things in detail: https://github.com/tower-rs/tower/issues/358
        Box::pin(async move {
            match verify_request.send().await {
                Ok(result) => {
                    if result.signature_valid() {
                        inner.call(request).await
                    } else {
                        // Note we're using `Ok` here instead of `Err` like your code was using,
                        // because we want to render an actual response. Even though it was a
                        // failed request, we successfully identified the error and can return a
                        // response, so this is a success scenario.
                        // `Err` could be used, for example, if you wanted to return an error type
                        // to the layer that wraps this layer, which could know how to transform it
                        // into a response.
                        Ok(http::Response::builder()
                            .status(http::StatusCode::BAD_REQUEST)
                            .header("Content-Type", "text/plain; charset=utf-8")
                            // Note here how we're converting `String`, which implements
                            // `http_body::Body`, into the body type that we've indicated in the
                            // layer's contract.
                            // https://docs.rs/aws-smithy-http-server/latest/aws_smithy_http_server/body/fn.boxed.html
                            .body(aws_smithy_http_server::body::boxed(String::from(
                                "Missing signature query parameter",
                            )))
                            .expect("failed to construct static response"))
                    }
                }
                Err(error) => Ok(http::Response::builder()
                    .status(http::StatusCode::BAD_REQUEST)
                    .header("Content-Type", "text/plain; charset=utf-8")
                    .body(aws_smithy_http_server::body::boxed(String::from(
                        "Failed verify signature",
                    )))
                    .expect("failed to construct static response")),
            }
        })
    }
}
