/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

//! An example module that defines "transparent" `MapRequest` layers used for testing purposes.

use aws_smithy_http::middleware::{AsyncMapRequest, MapRequest};
use aws_smithy_http::operation::Request;
use std::future::Future;
use std::pin::Pin;

/// A stage compatible with `MapRequest`/`AsyncMapRequest` used for testing service builders.
#[derive(Clone, Debug)]
pub struct TransparentStage;

impl MapRequest for TransparentStage {
    type Error = String;

    fn name(&self) -> &'static str {
        "transparent"
    }

    fn apply(&self, request: Request) -> Result<Request, Self::Error> {
        request.augment(|req, _conf| Ok(req))
    }
}

impl AsyncMapRequest for TransparentStage {
    type Error = String;
    type Future = Pin<Box<dyn Future<Output = Result<Request, String>> + Send + 'static>>;

    fn name(&self) -> &'static str {
        "transparent"
    }

    fn apply(&self, request: Request) -> Self::Future {
        Box::pin(async move { Ok(request) })
    }
}
