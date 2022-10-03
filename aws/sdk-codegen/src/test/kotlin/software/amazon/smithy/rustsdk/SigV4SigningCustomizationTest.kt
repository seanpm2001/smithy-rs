/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rustsdk

import org.junit.jupiter.api.Test
import software.amazon.smithy.aws.traits.auth.SigV4Trait
import software.amazon.smithy.rust.codegen.client.testutil.TestWorkspace
import software.amazon.smithy.rust.codegen.client.testutil.compileAndTest
import software.amazon.smithy.rust.codegen.client.testutil.stubConfigProject
import software.amazon.smithy.rust.codegen.client.testutil.unitTest

internal class SigV4SigningCustomizationTest {
    @Test
    fun `generates a valid config`() {
        val project = stubConfigProject(
            SigV4SigningConfig(
                AwsTestRuntimeConfig,
                true,
                SigV4Trait.builder().name("test-service").build(),
            ),
            TestWorkspace.testProject(),
        )
        project.lib {
            it.unitTest(
                "signing_service_override",
                """
                let conf = crate::config::Config::builder().build();
                assert_eq!(conf.signing_service(), "test-service");
                """,
            )
        }
        project.compileAndTest()
    }
}