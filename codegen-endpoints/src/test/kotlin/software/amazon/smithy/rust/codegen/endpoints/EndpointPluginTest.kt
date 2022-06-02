package software.amazon.smithy.rust.codegen.endpoints

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import software.amazon.smithy.aws.testutil.TestDiscovery
import software.amazon.smithy.rust.codegen.testutil.TestRuntimeConfig
import software.amazon.smithy.rust.codegen.testutil.TestWorkspace
import software.amazon.smithy.rust.codegen.testutil.compileAndTest
import software.amazon.smithy.rust.codegen.testutil.generate
import java.util.stream.Stream
import kotlin.streams.toList

class EndpointPluginTest {

    companion object {
        @JvmStatic
        fun testSuites(): Stream<TestDiscovery.RulesTestSuite> = TestDiscovery().testSuites()
    }

    @ParameterizedTest()
    @MethodSource("testSuites")
    fun `generate test suites`(testSuite: TestDiscovery.RulesTestSuite) {
        val project = TestWorkspace.testProject()
        EndpointsGenerator(testSuite.ruleset(), testSuite.testSuites(), TestRuntimeConfig).generate(project)
        project.generate()

    }

    @ParameterizedTest()
    @MethodSource("testSuites")
    fun `run test suites`(testSuite: TestDiscovery.RulesTestSuite) {
        val project = TestWorkspace.testProject()
        EndpointsGenerator(testSuite.ruleset(), testSuite.testSuites(), TestRuntimeConfig).generate(project)
        project.compileAndTest()
    }
}