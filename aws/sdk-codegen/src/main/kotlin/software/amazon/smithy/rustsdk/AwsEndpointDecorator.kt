/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rustsdk

import software.amazon.smithy.rust.codegen.client.smithy.ClientCodegenContext
import software.amazon.smithy.rust.codegen.client.smithy.customize.RustCodegenDecorator
import software.amazon.smithy.rust.codegen.client.smithy.endpoint.EndpointParamsGenerator
import software.amazon.smithy.rust.codegen.client.smithy.endpoint.EndpointRulesetIndex
import software.amazon.smithy.rust.codegen.client.smithy.endpoint.EndpointsModule
import software.amazon.smithy.rust.codegen.client.smithy.generators.config.ConfigCustomization
import software.amazon.smithy.rust.codegen.client.smithy.generators.config.ServiceConfig
import software.amazon.smithy.rust.codegen.client.smithy.generators.protocol.ClientProtocolGenerator
import software.amazon.smithy.rust.codegen.core.rustlang.CargoDependency
import software.amazon.smithy.rust.codegen.core.rustlang.Writable
import software.amazon.smithy.rust.codegen.core.rustlang.asType
import software.amazon.smithy.rust.codegen.core.rustlang.rust
import software.amazon.smithy.rust.codegen.core.rustlang.rustTemplate
import software.amazon.smithy.rust.codegen.core.rustlang.writable
import software.amazon.smithy.rust.codegen.core.smithy.CodegenContext
import software.amazon.smithy.rust.codegen.core.smithy.RuntimeConfig
import software.amazon.smithy.rust.codegen.core.smithy.RustCrate
import software.amazon.smithy.rust.codegen.core.smithy.generators.LibRsCustomization
import software.amazon.smithy.rust.codegen.core.smithy.generators.LibRsSection

class AwsEndpointDecorator : RustCodegenDecorator<ClientProtocolGenerator, ClientCodegenContext> {
    override val name: String = "AwsEndpoint"
    override val order: Byte = 0

    override fun configCustomizations(
        codegenContext: ClientCodegenContext,
        baseCustomizations: List<ConfigCustomization>,
    ): List<ConfigCustomization> {
        return baseCustomizations + EndpointConfigCustomization(
            codegenContext,
        )
    }

    override fun libRsCustomizations(
        codegenContext: ClientCodegenContext,
        baseCustomizations: List<LibRsCustomization>,
    ): List<LibRsCustomization> {
        return baseCustomizations + PubUseEndpoint(codegenContext.runtimeConfig)
    }

    override fun extras(codegenContext: ClientCodegenContext, rustCrate: RustCrate) {
        rustCrate.withModule(EndpointsModule) {
            rustTemplate(
                """
                /// Temporary shim to allow new and old endpoint resolvers to co-exist
                ##[doc(hidden)]
                impl From<#{Params}> for #{PlaceholderParams} {
                    fn from(params: #{Params}) -> Self {
                        Self::new(params.region().map(|r|#{Region}::new(r.to_string())))
                    }
                }
                """,
                "Params" to EndpointParamsGenerator(
                    EndpointRulesetIndex.of(codegenContext.model)
                        .endpointRulesForService(codegenContext.serviceShape)!!.parameters,
                ).paramsStruct(),
                "Region" to awsTypes(codegenContext.runtimeConfig).asType().member("region::Region"),
                "PlaceholderParams" to codegenContext.runtimeConfig.awsEndpoint().toType().member("Params"),
            )
        }
    }

    override fun supportsCodegenContext(clazz: Class<out CodegenContext>): Boolean =
        clazz.isAssignableFrom(ClientCodegenContext::class.java)
}

class EndpointConfigCustomization(
    codegenContext: CodegenContext,
) : ConfigCustomization() {
    private val runtimeConfig = codegenContext.runtimeConfig
    private val resolveAwsEndpoint = runtimeConfig.awsEndpoint().toType().copy(name = "ResolveAwsEndpoint")
    private val endpointShim = runtimeConfig.awsEndpoint().toType().member("EndpointShim")
    private val moduleUseName = codegenContext.moduleUseName()
    private val codegenScope = arrayOf(
        "ResolveAwsEndpoint" to resolveAwsEndpoint,
        "EndpointShim" to endpointShim,
        "aws_types" to awsTypes(runtimeConfig).toType(),
    )

    override fun section(section: ServiceConfig): Writable = writable {
        when (section) {
            ServiceConfig.BuilderImpl -> rustTemplate(
                """
                /// Overrides the endpoint resolver to use when making requests.
                ///
                /// When unset, the client will used a generated endpoint resolver based on the endpoint metadata
                /// for `$moduleUseName`.
                ///
                /// ## Examples
                /// ```no_run
                /// ## fn wrapper() -> Result<(), aws_smithy_http::endpoint::error::InvalidEndpointError> {
                /// use #{aws_types}::region::Region;
                /// use $moduleUseName::config::{Builder, Config};
                /// use $moduleUseName::Endpoint;
                ///
                /// let config = $moduleUseName::Config::builder()
                ///     .endpoint_resolver(Endpoint::immutable("http://localhost:8080")?)
                ///     .build();
                /// ## Ok(())
                /// ## }
                /// ```
                ##[deprecated(note = "use endpoint_url or set the endpoint resolver directly")]
                pub fn aws_endpoint_resolver(mut self, endpoint_resolver: impl #{ResolveAwsEndpoint} + 'static) -> Self {
                    self.endpoint_resolver = Some(std::sync::Arc::new(#{EndpointShim}::from_resolver(endpoint_resolver)) as _);
                    self
                }

                ##[deprecated(note = "use endpoint_url or set the endpoint resolver directly")]
                /// Sets the endpoint resolver to use when making requests.
                pub fn set_aws_endpoint_resolver(&mut self, endpoint_resolver: Option<std::sync::Arc<dyn #{ResolveAwsEndpoint}>>) -> &mut Self {
                    self.endpoint_resolver = endpoint_resolver.map(|res|std::sync::Arc::new(#{EndpointShim}::from_arc(res) ) as _);
                    self
                }
                """,
                *codegenScope,
            )

            else -> emptySection
        }
    }
}

class PubUseEndpoint(private val runtimeConfig: RuntimeConfig) : LibRsCustomization() {
    override fun section(section: LibRsSection): Writable {
        return when (section) {
            is LibRsSection.Body -> writable {
                rust(
                    "pub use #T::endpoint::Endpoint;",
                    CargoDependency.smithyHttp(runtimeConfig).toType(),
                )
            }

            else -> emptySection
        }
    }
}
