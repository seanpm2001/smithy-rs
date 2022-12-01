/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.client

import org.junit.jupiter.api.Test
import software.amazon.smithy.rust.codegen.client.testutil.clientIntegrationTest
import software.amazon.smithy.rust.codegen.core.testutil.asSmithyModel

class SmithyPlaygroundTest {
    val mySmithyModel = """
        namespace test
        use aws.protocols#restJson1

        @title("test")
        @restJson1
        @aws.api#service(sdkId: "Test", endpointPrefix: "service-with-prefix")
        service TestService {
            version: "123",
            operations: [Nop]
        }

        structure MyOpInput {
            param: String
        }

        @http(uri: "/foo", method: "POST")
        operation Nop {
            input: MyOpInput
        }
    """.asSmithyModel()

    @Test
    fun `generate this service`() {
        clientIntegrationTest(mySmithyModel) { _, _ ->
        }
    }
}
