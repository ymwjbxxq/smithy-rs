/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0.
 */

package software.amazon.smithy.rustsdk

import software.amazon.smithy.codegen.core.writer.CodegenWriterDelegator
import software.amazon.smithy.model.node.Node
import software.amazon.smithy.model.node.ObjectNode
import software.amazon.smithy.model.shapes.OperationShape
import software.amazon.smithy.model.shapes.ShapeId
import software.amazon.smithy.rust.codegen.rustlang.RustWriter
import software.amazon.smithy.rust.codegen.rustlang.raw
import software.amazon.smithy.rust.codegen.rustlang.rust
import software.amazon.smithy.rust.codegen.rustlang.rustBlock
import software.amazon.smithy.rust.codegen.smithy.generators.Instantiator
import software.amazon.smithy.rust.codegen.smithy.generators.ProtocolConfig
import software.amazon.smithy.rust.codegen.util.inputShape
import software.amazon.smithy.rust.codegen.util.orNull

class IntegrationTestGenerator(private val protocolConfig: ProtocolConfig, private val writers: CodegenWriterDelegator<RustWriter>) {
    private val e2eTests: Iterable<Node> = protocolConfig.serviceShape.findTrait("aws.e2e#e2eTests").orNull()?.toNode()?.expectArrayNode() ?: listOf()
    private val instantiator = Instantiator(protocolConfig.symbolProvider, protocolConfig.model, protocolConfig.runtimeConfig)
    fun render() {
        writers.useFileWriter("tests/integration.rs", "crate") { writer ->
            e2eTests.forEach { test ->
                renderTest(writer, test.expectObjectNode())
            }
        }
    }

    fun renderTest(writer: RustWriter, test: ObjectNode) {
        writer.raw("#[test]")
        writer.rustBlock("fn test_${test.expectMember("id").expectStringNode().value.replace('-', '_')}()") {
            val operations = test.expectArrayMember("operations")
            operations.map { it.expectObjectNode() }.forEach { operation ->
                val reqResp = operation.expectObjectMember("requestResponse")
                val request = reqResp.expectObjectMember("request")
                val ts = request.expectNumberMember("timestamp")
                val shape = request.expectObjectMember("shape")
                val shapeId = shape.expectStringMember("id").value
                val params = shape.expectObjectMember("params")
                val operationShape = protocolConfig.model.expectShape(ShapeId.from(shapeId), OperationShape::class.java)
                val (config, instantiation) = instantiator.renderInput(operationShape.inputShape(protocolConfig.model), params)
                config(this)
                rust("let mut input = ")
                instantiation(this)
                rust(";")
                rust(
                    """
                    input.config_mut().insert(std::time::UNIX_EPOCH + Duration::new(${ts.value}, 0));
                """
                )
            }
        }
    }
}
