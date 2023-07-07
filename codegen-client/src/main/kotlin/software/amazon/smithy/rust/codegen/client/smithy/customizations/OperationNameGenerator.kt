/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.client.smithy.customizations

import software.amazon.smithy.model.shapes.OperationShape
import software.amazon.smithy.rust.codegen.client.smithy.ClientCodegenContext
import software.amazon.smithy.rust.codegen.client.smithy.generators.OperationCustomization
import software.amazon.smithy.rust.codegen.client.smithy.generators.OperationSection
import software.amazon.smithy.rust.codegen.core.rustlang.Writable
import software.amazon.smithy.rust.codegen.core.rustlang.rustTemplate
import software.amazon.smithy.rust.codegen.core.rustlang.writable
import software.amazon.smithy.rust.codegen.core.util.dq

class OperationNameGenerator(private val codegenContext: ClientCodegenContext, private val operation: OperationShape) : OperationCustomization() {
    override fun section(section: OperationSection): Writable {
        return when (section) {
            is OperationSection.OperationConstants -> writable {
                val operationName = codegenContext.symbolProvider.toSymbol(operation).name

                rustTemplate(
                    """
                    /// The human-readable name of this operation.
                    pub const OPERATION_NAME: &str = ${operationName.dq()};
                    """,
                )
            }

            else -> emptySection
        }
    }
}
