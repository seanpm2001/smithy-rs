package software.amazon.smithy.rust.codegen.endpoints

import org.junit.jupiter.api.Test
import software.amazon.endpoints.s3.S3Rules
import software.amazon.smithy.aws.reterminus.EndpointTestSuite
import software.amazon.smithy.model.node.Node
import software.amazon.smithy.rust.codegen.testutil.TestRuntimeConfig
import software.amazon.smithy.rust.codegen.testutil.TestWorkspace
import software.amazon.smithy.rust.codegen.testutil.compileAndTest

class S3EndpointsTest {
    @Test
    fun `s3 rules compile and pass tests`() {
        val rules = S3Rules().ruleset()
        rules.typecheck()
        val project = TestWorkspace.testProject()
        val s3EndpointTests = EndpointTestSuite.fromNode(Node.parse(rules::class.java.getResource("/tests/s3-generated-tests.json").readText(), "s3-generated-tests.json"))
        EndpointsGenerator(rules, listOf(s3EndpointTests), TestRuntimeConfig).generate(project)
        project.compileAndTest()
    }
}