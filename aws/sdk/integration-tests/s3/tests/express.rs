/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

use std::time::{Duration, SystemTime};

use aws_sdk_s3::config::{Builder, Credentials};
use aws_sdk_s3::presigning::PresigningConfig;
use aws_sdk_s3::primitives::SdkBody;
use aws_sdk_s3::types::{
    BucketInfo, BucketType, CreateBucketConfiguration, DataRedundancy, LocationInfo, LocationType,
};
use aws_sdk_s3::{Client, Config};
use aws_smithy_runtime::client::http::test_util::dvr::ReplayingClient;
use aws_smithy_runtime::client::http::test_util::{
    capture_request, ReplayEvent, StaticReplayClient,
};
use aws_smithy_runtime::test_util::capture_test_logs::capture_test_logs;
use http::Uri;

async fn test_client<F>(update_builder: F) -> Client
where
    F: Fn(Builder) -> Builder,
{
    let sdk_config = aws_config::from_env()
        .no_credentials()
        .region("us-west-2")
        .load()
        .await;
    let config = Config::from(&sdk_config).to_builder().with_test_defaults();
    aws_sdk_s3::Client::from_conf(update_builder(config).build())
}

#[tokio::test]
async fn create_bucket() {
    let _logs = capture_test_logs();

    let http_client = ReplayingClient::from_file("tests/data/express/create-bucket.json").unwrap();
    let client = test_client(|b| b.http_client(http_client.clone())).await;

    let bucket_cfg = CreateBucketConfiguration::builder()
        .location(
            LocationInfo::builder()
                .name("usw2-az1")
                .r#type(LocationType::AvailabilityZone)
                .build(),
        )
        .bucket(
            BucketInfo::builder()
                .data_redundancy(DataRedundancy::SingleAvailabilityZone)
                .r#type(BucketType::Directory)
                .build(),
        )
        .build();

    let result = client
        .create_bucket()
        .create_bucket_configuration(bucket_cfg)
        .bucket("s3express-test-bucket--usw2-az1--x-s3")
        .send()
        .await;
    let result = dbg!(result).expect("success");
    assert_eq!(Some("https://s3express-test-bucket--usw2-az1--x-s3.s3express-usw2-az1.us-west-2.amazonaws.com/"), result.location());

    http_client
        .validate_body_and_headers(Some(&[]), "application/xml")
        .await
        .unwrap();
}

#[tokio::test]
async fn list_directory_buckets() {
    let _logs = capture_test_logs();

    let http_client =
        ReplayingClient::from_file("tests/data/express/list-directory-buckets.json").unwrap();
    let client = test_client(|b| b.http_client(http_client.clone())).await;

    let result = client.list_directory_buckets().send().await;
    dbg!(result).expect("success");

    http_client
        .validate_body_and_headers(Some(&[]), "application/xml")
        .await
        .unwrap();
}

#[tokio::test]
async fn list_objects_v2() {
    let _logs = capture_test_logs();

    let http_client =
        ReplayingClient::from_file("tests/data/express/list-objects-v2.json").unwrap();
    let client = test_client(|b| b.http_client(http_client.clone())).await;

    let result = client
        .list_objects_v2()
        .bucket("s3express-test-bucket--usw2-az1--x-s3")
        .send()
        .await;
    dbg!(result).expect("success");

    http_client
        .validate_body_and_headers(Some(&["x-amz-s3session-token"]), "application/xml")
        .await
        .unwrap();
}

#[tokio::test]
async fn mixed_auths() {
    let _logs = capture_test_logs();

    let http_client = ReplayingClient::from_file("tests/data/express/mixed-auths.json").unwrap();
    let client = test_client(|b| b.http_client(http_client.clone())).await;

    // A call to an S3 Express bucket where we should see two request/response pairs,
    // one for the `create_session` API and the other for `list_objects_v2` in S3 Express bucket.
    let result = client
        .list_objects_v2()
        .bucket("s3express-test-bucket--usw2-az1--x-s3")
        .send()
        .await;
    dbg!(result).expect("success");

    // A call to a regular bucket, and request headers should not contain `x-amz-s3session-token`.
    let result = client
        .list_objects_v2()
        .bucket("regular-test-bucket")
        .send()
        .await;
    dbg!(result).expect("success");

    // A call to another S3 Express bucket where we should again see two request/response pairs,
    // one for the `create_session` API and the other for `list_objects_v2` in S3 Express bucket.
    let result = client
        .list_objects_v2()
        .bucket("s3express-test-bucket-2--usw2-az3--x-s3")
        .send()
        .await;
    dbg!(result).expect("success");

    // This call should be an identity cache hit for the first S3 Express bucket,
    // thus no HTTP request should be sent to the `create_session` API.
    let result = client
        .list_objects_v2()
        .bucket("s3express-test-bucket--usw2-az1--x-s3")
        .send()
        .await;
    dbg!(result).expect("success");

    http_client
        .validate_body_and_headers(Some(&["x-amz-s3session-token"]), "application/xml")
        .await
        .unwrap();
}

fn create_session_request() -> http::Request<SdkBody> {
    http::Request::builder()
        .uri("https://s3express-test-bucket--usw2-az1--x-s3.s3express-usw2-az1.us-west-2.amazonaws.com/?session")
        .header("x-amz-create-session-mode", "ReadWrite")
        .method("GET")
        .body(SdkBody::empty())
        .unwrap()
}

fn create_session_response() -> http::Response<SdkBody> {
    http::Response::builder()
        .status(200)
        .body(SdkBody::from(
            r#"<?xml version="1.0" encoding="UTF-8"?>
            <CreateSessionResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                <Credentials>
                    <SessionToken>TESTSESSIONTOKEN</SessionToken>
                    <SecretAccessKey>TESTSECRETKEY</SecretAccessKey>
                    <AccessKeyId>ASIARTESTID</AccessKeyId>
                    <Expiration>2024-01-29T18:53:01Z</Expiration>
                </Credentials>
            </CreateSessionResult>
            "#,
        ))
        .unwrap()
}

#[tokio::test]
async fn presigning() {
    let http_client = StaticReplayClient::new(vec![ReplayEvent::new(
        create_session_request(),
        create_session_response(),
    )]);

    let client = test_client(|b| b.http_client(http_client.clone())).await;

    let presigning_config = PresigningConfig::builder()
        .start_time(SystemTime::UNIX_EPOCH + Duration::from_secs(1234567891))
        .expires_in(Duration::from_secs(30))
        .build()
        .unwrap();

    let presigned = client
        .get_object()
        .bucket("s3express-test-bucket--usw2-az1--x-s3")
        .key("ferris.png")
        .presigned(presigning_config)
        .await
        .unwrap();

    let uri = presigned.uri().parse::<Uri>().unwrap();

    let pq = uri.path_and_query().unwrap();
    let path = pq.path();
    let query = pq.query().unwrap();
    let mut query_params: Vec<&str> = query.split('&').collect();
    query_params.sort();

    pretty_assertions::assert_eq!(
        "s3express-test-bucket--usw2-az1--x-s3.s3express-usw2-az1.us-west-2.amazonaws.com",
        uri.authority().unwrap()
    );
    assert_eq!("GET", presigned.method());
    assert_eq!("/ferris.png", path);
    pretty_assertions::assert_eq!(
        &[
            "X-Amz-Algorithm=AWS4-HMAC-SHA256",
            "X-Amz-Credential=ASIARTESTID%2F20090213%2Fus-west-2%2Fs3express%2Faws4_request",
            "X-Amz-Date=20090213T233131Z",
            "X-Amz-Expires=30",
            "X-Amz-S3session-Token=TESTSESSIONTOKEN",
            "X-Amz-Signature=c09c93c7878184492cb960d59e148af932dff6b19609e63e3484599903d97e44",
            "X-Amz-SignedHeaders=host",
            "x-id=GetObject"
        ][..],
        &query_params
    );
    assert_eq!(presigned.headers().count(), 0);
}

#[tokio::test]
async fn presigning_with_express_session_auth_disabled() {
    let http_client = StaticReplayClient::new(vec![ReplayEvent::new(
        create_session_request(),
        create_session_response(),
    )]);

    let client = test_client(|b| {
        b.http_client(http_client.clone())
            .credentials_provider(Credentials::for_tests_with_session_token())
            .disable_s3_express_session_auth(true)
    })
    .await;

    let presigning_config = PresigningConfig::builder()
        .start_time(SystemTime::UNIX_EPOCH + Duration::from_secs(1234567891))
        .expires_in(Duration::from_secs(30))
        .build()
        .unwrap();

    let presigned = client
        .get_object()
        .bucket("s3express-test-bucket--usw2-az1--x-s3")
        .key("ferris.png")
        .presigned(presigning_config)
        .await
        .unwrap();

    let uri = presigned.uri().parse::<Uri>().unwrap();

    let pq = uri.path_and_query().unwrap();
    let path = pq.path();
    let query = pq.query().unwrap();
    let mut query_params: Vec<&str> = query.split('&').collect();
    query_params.sort();

    pretty_assertions::assert_eq!(
        "s3express-test-bucket--usw2-az1--x-s3.s3express-usw2-az1.us-west-2.amazonaws.com",
        uri.authority().unwrap()
    );
    assert_eq!("GET", presigned.method());
    assert_eq!("/ferris.png", path);
    // X-Amz-S3session-Token should not appear and X-Amz-Security-Token should be present instead
    pretty_assertions::assert_eq!(
        &[
            "X-Amz-Algorithm=AWS4-HMAC-SHA256",
            "X-Amz-Credential=ANOTREAL%2F20090213%2Fus-west-2%2Fs3express%2Faws4_request",
            "X-Amz-Date=20090213T233131Z",
            "X-Amz-Expires=30",
            "X-Amz-Security-Token=notarealsessiontoken",
            "X-Amz-Signature=8b088ab60bb8d3a3b9e7bcc69b32b3f1021ca2e3d1cbc7bdd413a030d9affee3",
            "X-Amz-SignedHeaders=host",
            "x-id=GetObject"
        ][..],
        &query_params
    );
    assert_eq!(presigned.headers().count(), 0);
}

#[tokio::test]
async fn support_customer_overriding_express_credentials_provider() {
    let (http_client, rx) = capture_request(None);
    let expected_session_token = "testsessiontoken";
    let client = test_client(|b| {
        b.http_client(http_client.clone())
            // Pass a credential with a session token so that
            // `x-amz-s3session-token` should appear in the request header.
            .express_credentials_provider(Credentials::new(
                "testaccess",
                "testsecret",
                Some(expected_session_token.to_owned()),
                None,
                "test",
            ))
    })
    .await;
    let _ = client
        .list_objects_v2()
        .bucket("s3express-test-bucket--usw2-az1--x-s3")
        .send()
        .await;

    let req = rx.expect_request();
    let actual_session_token = req
        .headers()
        .get("x-amz-s3session-token")
        .expect("x-amz-s3session-token should be present");
    assert_eq!(expected_session_token, actual_session_token);
}
