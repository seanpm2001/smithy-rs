#!/usr/bin/env bash
#
# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0
#

set -eux

SDK_PATH="$(git rev-parse --show-toplevel)"/aws/sdk/build/aws-sdk/sdk

pushd tools/ci-cdk/canary-runner

cargo run -- \
  run --rust-version ${RUST_VERSION} \
      --sdk-path "${SDK_PATH}" \
      --lambda-code-s3-bucket-name ${LAMBDA_CODE_S3_BUCKET_NAME} \
      --lambda-test-s3-bucket-name ${LAMBDA_TEST_S3_BUCKET_NAME} \
      --lambda-execution-role-arn ${LAMBDA_EXECUTION_ROLE_ARN} \
      --lambda-test-s3-mrap-bucket-arn ${LAMBDA_TEST_S3_MRAP_BUCKET_ARN} \
      --lambda-test-s3-express-bucket-name ${LAMBDA_TEST_S3_EXPRESS_BUCKET_NAME} \
      --musl

popd
