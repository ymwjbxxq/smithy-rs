package software.amazon.smithy.rust.codegen.endpoints

import software.amazon.smithy.aws.reterminus.EndpointRuleset
import software.amazon.smithy.aws.reterminus.EndpointTestSuite
import software.amazon.smithy.model.node.Node
import software.amazon.smithy.model.node.ObjectNode
import software.amazon.smithy.utils.IoUtils
import java.nio.file.Path

private const val ENDPOINTS_FILE = "endpointsFile"

private const val PARTITIONS_FILE = "partitionsFile"

private const val TESTS_FILE = "testsFile"

data class EndpointSettings(
    private val endpointsFile: Path,
    private val partitionsFile: Path,
    private val endpointTests: List<Path>
) {
    companion object {
        fun fromNode(node: ObjectNode): EndpointSettings {
            val endpointsFile = Path.of(node.expectStringMember(ENDPOINTS_FILE).value)
            val partitionsFile = Path.of(node.expectStringMember(PARTITIONS_FILE).value)
            val endpointTests = node.expectArrayMember(TESTS_FILE).map { n -> n.expectStringNode().value }.map(Path::of)
            return EndpointSettings(endpointsFile = endpointsFile, partitionsFile = partitionsFile, endpointTests)
        }
    }

    fun ruleset(): EndpointRuleset {
        val endpoints = IoUtils.readUtf8File(endpointsFile)
        return EndpointRuleset.fromNode(Node.parse(endpoints, endpointsFile.toString()))
    }

    fun tests(): List<EndpointTestSuite> {
        return endpointTests.map {
            EndpointTestSuite.fromNode(Node.parse(IoUtils.readUtf8File(it), it.toString()))
        }
    }
}