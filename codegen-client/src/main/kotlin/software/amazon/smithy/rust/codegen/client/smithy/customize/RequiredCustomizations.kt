/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.client.smithy.customize

import software.amazon.smithy.model.shapes.OperationShape
import software.amazon.smithy.rust.codegen.client.smithy.ClientCodegenContext
import software.amazon.smithy.rust.codegen.client.smithy.ClientRustModule
import software.amazon.smithy.rust.codegen.client.smithy.customizations.ConnectionPoisoningRuntimePluginCustomization
import software.amazon.smithy.rust.codegen.client.smithy.customizations.EndpointPrefixGenerator
import software.amazon.smithy.rust.codegen.client.smithy.customizations.HttpChecksumRequiredGenerator
import software.amazon.smithy.rust.codegen.client.smithy.customizations.HttpVersionListCustomization
import software.amazon.smithy.rust.codegen.client.smithy.customizations.IdempotencyTokenGenerator
import software.amazon.smithy.rust.codegen.client.smithy.customizations.IdentityConfigCustomization
import software.amazon.smithy.rust.codegen.client.smithy.customizations.InterceptorConfigCustomization
import software.amazon.smithy.rust.codegen.client.smithy.customizations.OperationNameGenerator
import software.amazon.smithy.rust.codegen.client.smithy.generators.OperationCustomization
import software.amazon.smithy.rust.codegen.client.smithy.generators.ServiceRuntimePluginCustomization
import software.amazon.smithy.rust.codegen.client.smithy.generators.config.ConfigCustomization
import software.amazon.smithy.rust.codegen.core.rustlang.Feature
import software.amazon.smithy.rust.codegen.core.rustlang.rustTemplate
import software.amazon.smithy.rust.codegen.core.smithy.RustCrate
import software.amazon.smithy.rust.codegen.core.smithy.customizations.AllowLintsCustomization
import software.amazon.smithy.rust.codegen.core.smithy.customizations.CrateVersionCustomization
import software.amazon.smithy.rust.codegen.core.smithy.customizations.pubUseSmithyErrorTypes
import software.amazon.smithy.rust.codegen.core.smithy.customizations.pubUseSmithyPrimitives
import software.amazon.smithy.rust.codegen.core.smithy.generators.LibRsCustomization
import software.amazon.smithy.rust.codegen.core.util.dq
import software.amazon.smithy.rust.codegen.core.util.serviceNameOrDefault

val TestUtilFeature = Feature("test-util", false, listOf())

/**
 * A set of customizations that are included in all protocols.
 *
 * This exists as a convenient place to gather these modifications, these are not true customizations.
 */
class RequiredCustomizations : ClientCodegenDecorator {
    override val name: String = "Required"
    override val order: Byte = -1

    override fun operationCustomizations(
        codegenContext: ClientCodegenContext,
        operation: OperationShape,
        baseCustomizations: List<OperationCustomization>,
    ): List<OperationCustomization> =
        baseCustomizations +
            IdempotencyTokenGenerator(codegenContext, operation) +
            EndpointPrefixGenerator(codegenContext, operation) +
            HttpChecksumRequiredGenerator(codegenContext, operation) +
            HttpVersionListCustomization(codegenContext, operation) +
            OperationNameGenerator(codegenContext, operation)

    override fun configCustomizations(
        codegenContext: ClientCodegenContext,
        baseCustomizations: List<ConfigCustomization>,
    ): List<ConfigCustomization> =
        if (codegenContext.smithyRuntimeMode.generateOrchestrator) {
            baseCustomizations +
                InterceptorConfigCustomization(codegenContext) +
                IdentityConfigCustomization(codegenContext)
        } else {
            baseCustomizations
        }

    override fun libRsCustomizations(
        codegenContext: ClientCodegenContext,
        baseCustomizations: List<LibRsCustomization>,
    ): List<LibRsCustomization> =
        baseCustomizations + AllowLintsCustomization()

    override fun extras(codegenContext: ClientCodegenContext, rustCrate: RustCrate) {
        // Add rt-tokio feature for `ByteStream::from_path`
        rustCrate.mergeFeature(Feature("rt-tokio", true, listOf("aws-smithy-http/rt-tokio")))
        rustCrate.mergeFeature(TestUtilFeature)

        rustCrate.withModule(ClientRustModule.Primitives) {
            pubUseSmithyPrimitives(codegenContext, codegenContext.model)(this)
        }
        rustCrate.withModule(ClientRustModule.Error) {
            pubUseSmithyErrorTypes(codegenContext)(this)
        }

        ClientRustModule.Meta.also { metaModule ->
            rustCrate.withModule(metaModule) {
                CrateVersionCustomization.extras(rustCrate, metaModule)

                rustTemplate(
                    """
                    /// The human-readable name of this service.
                    pub const SERVICE_NAME: &str = ${codegenContext.serviceShape.serviceNameOrDefault("the service").dq()};
                    """,
                )
            }
        }
    }

    override fun serviceRuntimePluginCustomizations(
        codegenContext: ClientCodegenContext,
        baseCustomizations: List<ServiceRuntimePluginCustomization>,
    ): List<ServiceRuntimePluginCustomization> = if (codegenContext.smithyRuntimeMode.generateOrchestrator) {
        baseCustomizations + ConnectionPoisoningRuntimePluginCustomization(codegenContext)
    } else {
        baseCustomizations
    }
}
