/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

//! Abstractions for Smithy
//! [XML Binding Traits](https://awslabs.github.io/smithy/1.0/spec/core/xml-traits.html)

#![allow(clippy::derive_partial_eq_without_eq)] // TODO: derive Eq for appropriate types

pub mod decode;
pub mod encode;
mod escape;
mod unescape;
