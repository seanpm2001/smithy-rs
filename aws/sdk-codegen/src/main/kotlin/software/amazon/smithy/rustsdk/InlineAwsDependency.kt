/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rustsdk

import software.amazon.smithy.rust.codegen.core.rustlang.InlineDependency
import software.amazon.smithy.rust.codegen.core.rustlang.RustDependency
import software.amazon.smithy.rust.codegen.core.rustlang.Visibility

object InlineAwsDependency {
    fun forRustFile(file: String, visibility: Visibility = Visibility.PRIVATE, vararg additionalDependency: RustDependency): InlineDependency =
        InlineDependency.forRustFile(file, "aws-inlineable", visibility, *additionalDependency)
}