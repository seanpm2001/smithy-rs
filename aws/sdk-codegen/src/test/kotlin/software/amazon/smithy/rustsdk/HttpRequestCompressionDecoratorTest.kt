/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rustsdk

import org.junit.jupiter.api.Test
import software.amazon.smithy.rust.codegen.core.rustlang.CargoDependency
import software.amazon.smithy.rust.codegen.core.rustlang.rustTemplate
import software.amazon.smithy.rust.codegen.core.smithy.RuntimeType
import software.amazon.smithy.rust.codegen.core.smithy.RuntimeType.Companion.preludeScope
import software.amazon.smithy.rust.codegen.core.testutil.asSmithyModel
import software.amazon.smithy.rust.codegen.core.testutil.integrationTest

class HttpRequestCompressionDecoratorTest {
    companion object {
        // Can't use the dollar sign in a multiline string with doing it like this.
        private const val PREFIX = "\$version: \"2\""
        val model =
            """
            $PREFIX
            namespace test

            use aws.api#service
            use aws.auth#sigv4
            use aws.protocols#restJson1
            use smithy.rules#endpointRuleSet

            @service(sdkId: "dontcare")
            @restJson1
            @sigv4(name: "dontcare")
            @auth([sigv4])
            @endpointRuleSet({
                "version": "1.0",
                "rules": [{ "type": "endpoint", "conditions": [], "endpoint": { "url": "https://example.com" } }],
                "parameters": {
                    "Region": { "required": false, "type": "String", "builtIn": "AWS::Region" },
                }
            })
            service TestService {
                version: "2023-01-01",
                operations: [SomeOperation]
            }

            @streaming
            blob StreamingBlob

            blob NonStreamingBlob

            @http(uri: "/SomeOperation", method: "POST")
            @optionalAuth
            @requestCompression(encodings: ["gzip"])
            operation SomeOperation {
                input: SomeInput,
                output: SomeOutput
            }

            @input
            structure SomeInput {
                @httpPayload
                @required
                body: NonStreamingBlob
            }

            @output
            structure SomeOutput {}

            @http(uri: "/SomeStreamingOperation", method: "POST")
            @optionalAuth
            @requestCompression(encodings: ["gzip"])
            operation SomeStreamingOperation {
                input: SomeStreamingInput,
                output: SomeStreamingOutput
            }

            @input
            structure SomeStreamingInput {
                @httpPayload
                @required
                body: StreamingBlob
            }

            @output
            structure SomeStreamingOutput {}
            """.asSmithyModel()
    }

    @Test
    fun smokeTestSdkCodegen() {
        awsSdkIntegrationTest(model) { _, _ ->
            // it should compile
        }
    }

    @Test
    fun requestCompressionWorks() {
        awsSdkIntegrationTest(model) { context, rustCrate ->
            val rc = context.runtimeConfig
            val moduleName = context.moduleUseName()
            rustCrate.integrationTest("request_compression") {
                rustTemplate(
                    """
                    ##![cfg(feature = "test-util")]

                    use #{ByteStream};
                    use #{Blob};
                    use #{Region};

                    const UNCOMPRESSED_INPUT: &[u8] = b"Action=PutMetricData&Version=2010-08-01&Namespace=Namespace&MetricData.member.1.MetricName=metric&MetricData.member.1.Unit=Bytes&MetricData.member.1.Value=128";
                    // This will break if we ever change the default compression level.
                    const COMPRESSED_OUTPUT: &[u8] = &[
                        31, 139, 8, 0, 0, 0, 0, 0, 0, 255, 1, 115, 0, 140, 255, 31, 139, 8, 0, 0, 0, 0, 0, 0, 255, 109,
                        139, 49, 14, 128, 32, 16, 4, 127, 67, 39, 1, 43, 155, 43, 52, 182, 26, 27, 233, 79, 114, 5,
                        137, 160, 129, 163, 240, 247, 6, 77, 180, 161, 155, 204, 206, 246, 150, 221, 17, 96, 201, 60,
                        17, 71, 103, 71, 100, 20, 134, 98, 42, 182, 85, 90, 53, 170, 107, 148, 22, 51, 122, 74, 39, 90,
                        130, 143, 196, 255, 144, 158, 252, 70, 81, 106, 249, 186, 210, 128, 127, 176, 90, 173, 193, 49,
                        12, 23, 83, 170, 206, 6, 247, 76, 160, 219, 238, 6, 30, 221, 9, 253, 158, 0, 0, 0, 160, 51, 48,
                        147, 115, 0, 0, 0,
                    ];

                    ##[#{tokio}::test]
                    async fn test_request_compression() {
                        let (http_client, rx) = #{capture_request}(None);
                        let config = $moduleName::Config::builder()
                            .region(Region::from_static("doesntmatter"))
                            .with_test_defaults()
                            .http_client(http_client)
                            .build();
                        let client = $moduleName::Client::from_conf(config);
                        let _ = client.some_operation().body(Blob::new(UNCOMPRESSED_INPUT)).send().await;
                        let request = rx.expect_request();
                        // Check that the content-encoding header is set to "gzip"
                        assert_eq!("gzip", request.headers().get("content-encoding").unwrap());

                        let compressed_body = ByteStream::from(request.into_body()).collect().await.unwrap().to_vec();
                        // Assert input body was compressed
                        assert_eq!(COMPRESSED_OUTPUT, compressed_body.as_slice())
                    }

                    ##[#{tokio}::test]
                    async fn test_request_compression_can_be_disabled() {
                        let (http_client, rx) = ::aws_smithy_runtime::client::http::test_util::capture_request(None);
                        let config = $moduleName::Config::builder()
                            .region(Region::from_static("doesntmatter"))
                            .with_test_defaults()
                            .http_client(http_client)
                            .disable_request_compression(true)
                            .build();

                        let client = $moduleName::Client::from_conf(config);
                        let _ = client.some_operation().body(Blob::new(UNCOMPRESSED_INPUT)).send().await;
                        let request = rx.expect_request();
                        // Check that the content-encoding header is not set to "gzip"
                        assert_ne!(Some("gzip"), request.headers().get("content-encoding"));

                        let compressed_body = ByteStream::from(request.into_body()).collect().await.unwrap().to_vec();
                        // Assert input body was not compressed
                        assert_eq!(UNCOMPRESSED_INPUT, compressed_body.as_slice())
                    }

                    ##[#{tokio}::test]
                    async fn test_request_min_size_body_over_minimum() {
                        let (http_client, rx) = ::aws_smithy_runtime::client::http::test_util::capture_request(None);
                        let config = $moduleName::Config::builder()
                            .region(Region::from_static("doesntmatter"))
                            .with_test_defaults()
                            .http_client(http_client)
                            .disable_request_compression(false)
                            .request_min_compression_size_bytes(128)
                            .build();

                            let client = $moduleName::Client::from_conf(config);
                        let _ = client.some_operation().body(Blob::new(UNCOMPRESSED_INPUT)).send().await;
                        let request = rx.expect_request();
                        // Check that the content-encoding header is set to "gzip"
                        assert_eq!("gzip", request.headers().get("content-encoding").unwrap());

                        let compressed_body = ByteStream::from(request.into_body()).collect().await.unwrap().to_vec();
                        // Assert input body was compressed
                        assert_eq!(COMPRESSED_OUTPUT, compressed_body.as_slice())
                    }

                    ##[#{tokio}::test]
                    async fn test_request_min_size_body_under_minimum() {
                        let (http_client, rx) = ::aws_smithy_runtime::client::http::test_util::capture_request(None);
                        let config = $moduleName::Config::builder()
                            .region(Region::from_static("doesntmatter"))
                            .with_test_defaults()
                            .http_client(http_client)
                            .disable_request_compression(false)
                            .request_min_compression_size_bytes(256)
                            .build();

                            let client = $moduleName::Client::from_conf(config);
                        let _ = client.some_operation().body(Blob::new(UNCOMPRESSED_INPUT)).send().await;
                        let request = rx.expect_request();
                        // Check that the content-encoding header is not set to "gzip"
                        assert_ne!(Some("gzip"), request.headers().get("content-encoding"));

                        let compressed_body = ByteStream::from(request.into_body()).collect().await.unwrap().to_vec();
                        // Assert input body was not compressed
                        assert_eq!(UNCOMPRESSED_INPUT, compressed_body.as_slice())
                    }

                    ##[#{tokio}::test]
                    async fn test_request_compression_implicitly_enabled() {
                        let (http_client, rx) = ::aws_smithy_runtime::client::http::test_util::capture_request(None);
                        let config = $moduleName::Config::builder()
                            .region(Region::from_static("doesntmatter"))
                            .with_test_defaults()
                            .http_client(http_client)
                            .request_min_compression_size_bytes(128)
                            .build();

                            let client = $moduleName::Client::from_conf(config);
                        let _ = client.some_operation().body(Blob::new(UNCOMPRESSED_INPUT)).send().await;
                        let request = rx.expect_request();
                        // Check that the content-encoding header is set to "gzip"
                        assert_eq!("gzip", request.headers().get("content-encoding").unwrap());

                        let compressed_body = ByteStream::from(request.into_body()).collect().await.unwrap().to_vec();
                        // Assert input body was compressed
                        assert_eq!(COMPRESSED_OUTPUT, compressed_body.as_slice())
                    }

                    ##[#{tokio}::test]
                    async fn test_request_compression_min_size_default() {
                        let (http_client, rx) = ::aws_smithy_runtime::client::http::test_util::capture_request(None);
                        let config = $moduleName::Config::builder()
                            .region(Region::from_static("doesntmatter"))
                            .with_test_defaults()
                            .http_client(http_client)
                            .disable_request_compression(false)
                            .build();

                            let client = $moduleName::Client::from_conf(config);
                        let _ = client.some_operation().body(Blob::new(UNCOMPRESSED_INPUT)).send().await;
                        let request = rx.expect_request();
                        // Check that the content-encoding header is not set to "gzip"
                        assert_ne!(Some("gzip"), request.headers().get("content-encoding"));

                        let compressed_body = ByteStream::from(request.into_body()).collect().await.unwrap().to_vec();
                        // Assert input body was not compressed
                        assert_eq!(UNCOMPRESSED_INPUT, compressed_body.as_slice())
                    }

                    ##[#{tokio}::test]
                    async fn test_request_compression_streaming_body() {
                        let (http_client, rx) = ::aws_smithy_runtime::client::http::test_util::capture_request(None);
                        let config = $moduleName::Config::builder()
                            .region(Region::from_static("doesntmatter"))
                            .with_test_defaults()
                            .http_client(http_client)
                            .disable_request_compression(false)
                            .build();

                            let client = $moduleName::Client::from_conf(config);
                        let _ = client.some_operation().body(Blob::new(UNCOMPRESSED_INPUT)).send().await;
                        let request = rx.expect_request();
                        // Check that the content-encoding header is set to "gzip"
                        assert_eq!("gzip", request.headers().get("content-encoding").unwrap());

                        let compressed_body = ByteStream::from(request.into_body()).collect().await.unwrap().to_vec();
                        // Assert input body was compressed
                        assert_eq!(COMPRESSED_OUTPUT, compressed_body.as_slice())
                    }
                    """,
                    *preludeScope,
                    "ByteStream" to RuntimeType.smithyTypes(rc).resolve("byte_stream::ByteStream"),
                    "Blob" to RuntimeType.smithyTypes(rc).resolve("Blob"),
                    "Region" to AwsRuntimeType.awsTypes(rc).resolve("region::Region"),
                    "tokio" to CargoDependency.Tokio.toType(),
                    "capture_request" to RuntimeType.captureRequest(rc),
                )
            }
        }
    }
}
