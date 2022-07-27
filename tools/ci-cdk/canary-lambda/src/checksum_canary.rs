/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

use crate::canary::{CanaryError, Clients};
use crate::{mk_canary, CanaryEnv};

use aws_sdk_s3 as s3;
use s3::error::{GetObjectError, GetObjectErrorKind};
use s3::types::{ByteStream, SdkError};
use s3::model::{ChecksumAlgorithm, ChecksumMode};
use aws_smithy_http::{byte_stream::ByteStream, body::SdkBody};

use anyhow::Context;
use uuid::Uuid;

const EXPECTED_CRC32C_CHECKSUM: &str = "some   value";
const EXPECTED_BODY: &str = "Hello world";

mk_canary!("checksum", |clients: &Clients, env: &CanaryEnv| {
    checksum_canary(clients.s3.clone(), env.s3_bucket_name.clone())
    // streaming_checksum_canary(clients.s3.clone(), env.s3_bucket_name.clone());
});

pub async fn checksum_canary(client: s3::Client, s3_bucket_name: String) -> anyhow::Result<()> {
    let body = ByteStream::from_static(EXPECTED_BODY.as_bytes());
    test_checksum(client, s3_bucket_name, body).await
}

pub async fn streaming_checksum_canary(client: s3::Client, s3_bucket_name: String) -> anyhow::Result<()> {
    // All this song and dance is necessary to create a streaming body from some text.
    let chunks: Vec<Result<_, std::io::Error>> = vec![
        Ok(EXPECTED_BODY),
    ];
    let stream = futures_util::stream::iter(chunks);
    let body = hyper::Body::wrap_stream(stream);
    let body = SdkBody::from(body);
    let body = ByteStream::from(body);
    test_checksum(client, s3_bucket_name, body).await
}

pub async fn test_checksum(client: s3::Client, s3_bucket_name: String, body: ByteStream) -> anyhow::Result<()> {
    let test_key = Uuid::new_v4().as_u128().to_string();

    // Look for the test object and expect that it doesn't exist
    match client
        .get_object()
        .bucket(&s3_bucket_name)
        .key(&test_key)
        .send()
        .await
    {
        Ok(_) => {
            return Err(
                CanaryError(format!("Expected object {} to not exist in S3", test_key)).into(),
            );
        }
        Err(SdkError::ServiceError {
                err:
                GetObjectError {
                    kind: GetObjectErrorKind::NoSuchKey { .. },
                    ..
                },
                ..
            }) => {
            // good
        }
        Err(err) => {
            Err(err).context("unexpected s3::GetObject failure")?;
        }
    }

    // Put the test object
    client
        .put_object()
        .bucket(&s3_bucket_name)
        .key(&test_key)
        .body(body)
        .checksum_algorithm(ChecksumAlgorithm::Crc32C)
        .send()
        .await
        .context("s3::PutObject")?;

    // Get the test object and verify it looks correct
    let output = client
        .get_object()
        .bucket(&s3_bucket_name)
        .key(&test_key)
        .checksum_mode(ChecksumMode::Enabled)
        .send()
        .await
        .context("s3::GetObject[2]")?;

    let mut result = Ok(());
    match output.checksum_crc32_c() {
        Some(checksum) => {
            if checksum != EXPECTED_CRC32C_CHECKSUM {
                result = Err(CanaryError(format!(
                    "S3 CRC32C checksum was incorrect. Expected `{}` but got `{}`.",
                    EXPECTED_CRC32C_CHECKSUM, value
                ))
                    .into());
            }
        }
        None => {
            result = Err(CanaryError("S3 CRC32C checksum was missing".into()).into());
        }
    }

    let payload = output
        .body
        .collect()
        .await
        .context("download s3::GetObject[2] body")?
        .into_bytes();
    if std::str::from_utf8(payload.as_ref()).context("s3 payload")? != EXPECTED_BODY {
        result = Err(CanaryError("S3 object body didn't match what was put there".into()).into());
    }

    // Delete the test object
    client
        .delete_object()
        .bucket(&s3_bucket_name)
        .key(&test_key)
        .send()
        .await
        .context("s3::DeleteObject")?;

    result
}

// This test runs against an actual AWS account. Comment out the `ignore` to run it.
// Be sure to set the `TEST_S3_BUCKET` environment variable to the S3 bucket to use,
// and also make sure the credential profile sets the region (or set `AWS_DEFAULT_PROFILE`).
#[cfg(test)]
#[tokio::test]
async fn test_checksum_canary() {
    let config = aws_config::load_from_env().await;
    let client = s3::Client::new(&config);
    checksum_canary(
        client,
        std::env::var("TEST_S3_BUCKET").expect("TEST_S3_BUCKET must be set"),
    )
        .await
        .expect("success");
}

// This test runs against an actual AWS account. Comment out the `ignore` to run it.
// Be sure to set the `TEST_S3_BUCKET` environment variable to the S3 bucket to use,
// and also make sure the credential profile sets the region (or set `AWS_DEFAULT_PROFILE`).
#[ignore]
#[cfg(test)]
#[tokio::test]
async fn test_streaming_checksum_canary() {
    let config = aws_config::load_from_env().await;
    let client = s3::Client::new(&config);
    streaming_checksum_canary(
        client,
        std::env::var("TEST_S3_BUCKET").expect("TEST_S3_BUCKET must be set"),
    )
        .await
        .expect("success");
}
