/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0.
 */

package software.amazon.smithy.rustsdk

import software.amazon.smithy.codegen.core.Symbol
import software.amazon.smithy.codegen.core.writer.CodegenWriterDelegator
import software.amazon.smithy.model.node.Node
import software.amazon.smithy.model.node.ObjectNode
import software.amazon.smithy.model.shapes.OperationShape
import software.amazon.smithy.model.shapes.Shape
import software.amazon.smithy.model.shapes.ShapeId
import software.amazon.smithy.model.traits.ErrorTrait
import software.amazon.smithy.rust.codegen.rustlang.CargoDependency
import software.amazon.smithy.rust.codegen.rustlang.CratesIo
import software.amazon.smithy.rust.codegen.rustlang.Local
import software.amazon.smithy.rust.codegen.rustlang.RustWriter
import software.amazon.smithy.rust.codegen.rustlang.raw
import software.amazon.smithy.rust.codegen.rustlang.rust
import software.amazon.smithy.rust.codegen.rustlang.rustBlock
import software.amazon.smithy.rust.codegen.rustlang.withBlock
import software.amazon.smithy.rust.codegen.smithy.RuntimeType
import software.amazon.smithy.rust.codegen.smithy.RustSymbolProvider
import software.amazon.smithy.rust.codegen.smithy.WrappingSymbolProvider
import software.amazon.smithy.rust.codegen.smithy.generators.Instantiator
import software.amazon.smithy.rust.codegen.smithy.generators.ProtocolConfig
import software.amazon.smithy.rust.codegen.util.dq
import software.amazon.smithy.rust.codegen.util.inputShape
import software.amazon.smithy.rust.codegen.util.orNull

data class Header(val key: String, val value: String) {
    companion object {
        fun fromNode(node: ObjectNode): Header {
            return Header(node.expectStringMember("key").value, node.expectStringMember("value").value)
        }
    }
}

data class E2eTest(
    val id: String,
    val environment: TestEnvironment,
    val operations: List<Operation>,
    val networkEvents: List<NetworkEvent>
) {
    companion object {

        fun fromNode(node: Node) = node.let { it.expectObjectNode() }.let {
            E2eTest(
                id = it.expectStringMember("id").value,
                environment = TestEnvironment.fromNode(it.expectObjectMember("environment")),
                operations = it.expectArrayMember("operations").map { Operation.fromNode(it.expectObjectNode()) },
                networkEvents = it.expectArrayMember("networkTraffic").map {
                    NetworkEvent.fromNode(it.expectObjectNode())
                }
            )
        }
    }
}

data class TestEnvironment(val environment: Map<String, String>) {
    companion object {
        fun fromNode(node: ObjectNode) =
            TestEnvironment(node.expectObjectMember("environment").stringMap.mapValues { it.value.expectStringNode().value })
    }
}

data class HttpRequest(
    val headers: List<Header>,
    val body: String,
    val timestamp: Double,
    val method: String,
    val uri: String
) {
    companion object {
        fun fromNode(node: ObjectNode): HttpRequest {
            return HttpRequest(
                headers = node.expectArrayMember("headers").map { Header.fromNode(it.expectObjectNode()) },
                body = node.expectStringMember("body").value,
                timestamp = node.expectNumberMember("timestamp").value.toDouble(),
                method = node.expectStringMember("method").value,
                uri = node.expectStringMember("uri").value
            )
        }
    }
}

sealed class Operation {
    data class RequestResponse(val operation: RROperation) : Operation()
    data class PaginateToEnd(val operation: RROperation) : Operation()
    companion object {
        fun fromNode(node: ObjectNode) = when {
            node.containsMember("requestResponse") -> RequestResponse(RROperation.fromNode(node.expectObjectMember("requestResponse")))
            else -> TODO()
        }
    }
}

data class Request(val shape: ParameterizedShape, val timestamp: Int) {
    companion object {
        fun fromNode(node: ObjectNode) = Request(
            ParameterizedShape.fromNode(node.expectObjectMember("shape")),
            node.expectNumberMember("timestamp").value.toInt()
        )
    }
}

sealed class Response {
    data class Success(val response: ParameterizedShape) : Response()
    data class UnhandledError(val message: String) : Response()
    companion object {
        fun fromNode(node: ObjectNode) = when {
            node.containsMember("success") -> Success(ParameterizedShape.fromNode(node.expectObjectMember("success")))
            node.containsMember("failure") -> UnhandledError(node.expectStringMember("failure").value)
            else -> throw Exception("unreachable")
        }
    }
}

data class ParameterizedShape(val id: String, val params: ObjectNode) {
    companion object {
        fun fromNode(node: ObjectNode) =
            ParameterizedShape(node.expectStringMember("id").value, node.expectObjectMember("params"))
    }
}

data class RROperation(val request: Request, val response: Response) {
    companion object {
        fun fromNode(node: ObjectNode) = RROperation(
            Request.fromNode(node.expectObjectMember("request")),
            Response.fromNode(node.expectObjectMember("response"))
        )
    }
}

data class HttpResponse(
    val headers: List<Header>,
    val body: String,
    val timestamp: Int,
    val status: Int
) {
    companion object {
        fun fromNode(node: ObjectNode) = HttpResponse(
            headers = node.expectArrayMember("headers").map { Header.fromNode(it.expectObjectNode()) },
            body = node.expectStringMember("body").value,
            timestamp = node.expectNumberMember("timestamp").value.toInt(),
            status = node.expectNumberMember("status").value.toInt()
        )
    }
}

data class NetworkEvent(val httpRequest: HttpRequest, val httpResponse: HttpResponse) {
    companion object {
        fun fromNode(node: ObjectNode) = NetworkEvent(
            httpRequest = HttpRequest.fromNode(node.expectObjectMember("httpRequest")),
            httpResponse = HttpResponse.fromNode(node.expectObjectMember("httpResponse"))
        )
    }
}

class RemoteSymbolProvider(private val base: RustSymbolProvider) : WrappingSymbolProvider(base) {
    override fun toSymbol(shape: Shape): Symbol {
        val inner = base.toSymbol(shape)
        val ns = inner.namespace.replace("crate::", "dynamodb::")
        return inner.toBuilder().namespace(ns, "::").build()
    }
}

class IntegrationTestGenerator(
    private val protocolConfig: ProtocolConfig,
    private val writers: CodegenWriterDelegator<RustWriter>
) {
    private val e2eTests: Iterable<Node> =
        protocolConfig.serviceShape.findTrait("aws.e2e#e2eTests").orNull()?.toNode()?.expectArrayNode() ?: listOf()
    private val instantiator =
        Instantiator(
            RemoteSymbolProvider(protocolConfig.symbolProvider),
            protocolConfig.model,
            protocolConfig.runtimeConfig
        )

    fun render() {
        writers.useFileWriter("tests/integration.rs", "crate") { writer ->
            e2eTests.map { E2eTest.fromNode(it) }.forEach { test ->
                renderTest(writer, test)
            }
        }
    }

    fun renderTest(writer: RustWriter, test: E2eTest) {
        writer.addDependency(Tokio)
        writer.addDependency(SerialTest)
        writer.raw("#[tokio::test]")
        writer.raw("#[serial_test::serial]")
        writer.rustBlock("async fn test_${test.id.replace('-', '_')}()") {
            renderEnvironment(test.environment)
            renderNetwork(test.networkEvents)
            renderOperations(test.operations)
        }
    }

    fun RustWriter.renderEnvironment(environment: TestEnvironment) {
        environment.environment.forEach { (k, v) ->
            rust("std::env::set_var(${k.dq()}, ${v.dq()});")
        }
    }

    fun RustWriter.renderNetwork(networkEvents: List<NetworkEvent>) {
        withBlock("let events = vec![", "];") {
            networkEvents.forEach { event ->
                val request = event.httpRequest
                val response = event.httpResponse
                withBlock("(", ")") {
                    // request
                    rust("#T::new()", RuntimeType.HttpRequestBuilder)
                    rust(".method(${request.method.dq()})")
                    request.headers.forEach {
                        rust(".header(${it.key.dq()}, ${it.value.dq()})")
                    }
                    rust(
                        ".body(#T::from(${request.body.dq()})).unwrap()",
                        RuntimeType.SdkBody(protocolConfig.runtimeConfig)
                    )
                    rust(",")

                    // response
                    rust("#T::new()", RuntimeType.HttpResponseBuilder)
                    rust(".status(${response.status})")
                    response.headers.forEach {
                        rust(".header(${it.key.dq()}, ${it.value.dq()})")
                    }
                    rust(".body(${response.body.dq()}).unwrap()")
                }
            }
        }

        rust("let test_connection = #T::new(events);", TestConnection)
        rust("let client = #T::new(test_connection);", HyperClient)
    }

    private fun RustWriter.renderOperations(operations: List<Operation>) {
        operations.forEach { operation ->
            check(operation is Operation.RequestResponse)
            val reqResp = operation.operation
            val request = reqResp.request
            val shapeId = request.shape.id
            val params = request.shape.params
            val operationShape = protocolConfig.model.expectShape(ShapeId.from(shapeId), OperationShape::class.java)
            val (config, instantiation) = instantiator.renderInput(
                operationShape.inputShape(protocolConfig.model),
                params
            )
            rust("""let config = dynamodb::Config::builder().build();""")
            rust("let mut input = ")
            instantiation(this)
            rust(";")
            rust(
                """
                        input.config_mut().insert(std::time::UNIX_EPOCH + std::time::Duration::new(${request.timestamp}, 0));
                    """
            )
            rust("let response = client.call(input).await;")
            outputExpectation(reqResp.response)
        }
    }

    private fun RustWriter.outputExpectation(response: Response) {
        when (response) {
            is Response.Success -> {
                val responseShape = protocolConfig.model.expectShape(ShapeId.from(response.response.id))
                withBlock("let expected_output = ", ";") {
                    instantiator.render(this, responseShape, response.response.params)
                }
                if (responseShape.hasTrait(ErrorTrait::class.java)) {
                    rust("/* todo err */")
                } else {
                    rust("""assert_eq!(response.expect("Should be success"), expected_output);""")
                }
            }
            is Response.UnhandledError -> rust("response.expect_err(${response.message.dq()})")
        }
    }
}

val Tokio = CargoDependency("tokio", CratesIo("1"), features = listOf("full"))
val SerialTest = CargoDependency("serial_test", CratesIo("0.5.1"))
val AwsHyper = CargoDependency("aws-hyper", Local("../"))
val HyperClient = RuntimeType("Client", AwsHyper, "aws_hyper")
val ItHelper = CargoDependency("integration-test-helper", Local("../"))
val TestConnection = RuntimeType("TestConnection", ItHelper, "integration_test_helper")
