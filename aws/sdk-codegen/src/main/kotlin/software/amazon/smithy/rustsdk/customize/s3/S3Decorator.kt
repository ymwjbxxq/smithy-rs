/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rustsdk.customize.s3

import software.amazon.smithy.aws.reterminus.EndpointTestSuite
import software.amazon.smithy.aws.s3.S3Rules
import software.amazon.smithy.aws.traits.protocols.RestXmlTrait
import software.amazon.smithy.model.node.Node
import software.amazon.smithy.model.shapes.OperationShape
import software.amazon.smithy.model.shapes.ShapeId
import software.amazon.smithy.rust.codegen.endpoints.EndpointsGenerator
import software.amazon.smithy.rust.codegen.rustlang.CargoDependency
import software.amazon.smithy.rust.codegen.rustlang.RustModule
import software.amazon.smithy.rust.codegen.rustlang.Writable
import software.amazon.smithy.rust.codegen.rustlang.asType
import software.amazon.smithy.rust.codegen.rustlang.rust
import software.amazon.smithy.rust.codegen.rustlang.rustBlockTemplate
import software.amazon.smithy.rust.codegen.rustlang.rustTemplate
import software.amazon.smithy.rust.codegen.rustlang.writable
import software.amazon.smithy.rust.codegen.smithy.CodegenContext
import software.amazon.smithy.rust.codegen.smithy.RuntimeType
import software.amazon.smithy.rust.codegen.smithy.customize.RustCodegenDecorator
import software.amazon.smithy.rust.codegen.smithy.generators.LibRsCustomization
import software.amazon.smithy.rust.codegen.smithy.generators.LibRsSection
import software.amazon.smithy.rust.codegen.smithy.generators.config.ConfigCustomization
import software.amazon.smithy.rust.codegen.smithy.letIf
import software.amazon.smithy.rust.codegen.smithy.protocols.ProtocolMap
import software.amazon.smithy.rust.codegen.smithy.protocols.RestXml
import software.amazon.smithy.rust.codegen.smithy.protocols.RestXmlFactory
import software.amazon.smithy.rust.codegen.testutil.TestWorkspace
import software.amazon.smithy.rustsdk.AwsRuntimeType
import software.amazon.smithy.rustsdk.EndpointConfigCustomization
import software.amazon.smithy.rustsdk.awsEndpoint

/**
 * Top level decorator for S3
 * */
class S3Decorator : RustCodegenDecorator {
    override val name: String = "S3ExtendedError"
    override val order: Byte = 0

    private fun applies(serviceId: ShapeId) =
        serviceId == ShapeId.from("com.amazonaws.s3#AmazonS3")

    override fun protocols(serviceId: ShapeId, currentProtocols: ProtocolMap): ProtocolMap {
        return currentProtocols.letIf(applies(serviceId)) {
            it + mapOf(
                RestXmlTrait.ID to RestXmlFactory { protocolConfig ->
                    S3(protocolConfig)
                }
            )
        }
    }

    override fun libRsCustomizations(
        codegenContext: CodegenContext,
        baseCustomizations: List<LibRsCustomization>
    ): List<LibRsCustomization> {
        return baseCustomizations.letIf(applies(codegenContext.serviceShape.id)) {
            it + S3PubUse()
        }
    }

    override fun configCustomizations(
        codegenContext: CodegenContext,
        baseCustomizations: List<ConfigCustomization>
    ): List<ConfigCustomization> {
        val removeDefaultEndpoint = baseCustomizations.filter { it !is EndpointConfigCustomization }
        val rules = S3Rules().ruleset()
        rules.typecheck()
        val s3EndpointTests = EndpointTestSuite.fromNode(Node.parse(rules::class.java.getResource("/tests/s3-generated-tests.json").readText()))
        val generator = EndpointsGenerator(S3Rules().ruleset(), listOf(s3EndpointTests), codegenContext.runtimeConfig)
        val resolveAwsEndpoint = codegenContext.runtimeConfig.awsEndpoint().asType().member("ResolveAwsEndpointV2")
        val awsEndpoint = codegenContext.runtimeConfig.awsEndpoint().asType().member("AwsEndpoint")
        val s3EndpointResolver = RuntimeType.forInlineFun("Resolver", RustModule.public("endpoint_resolver")) { writer ->
            writer.rustTemplate("""
                struct Resolver;
                impl #{ResolveAwsEndpointV2}<#{Params}> for Resolver {
                    fn resolve_endpoint(&self, params: &{Params}) -> Result<{AwsEndpoint}, BoxError> {
                        let uri = #{resolve_endpoint}(params)?;
                        
                        
                    }
                    
                }
                
            """,
                "Params" to generator.params(),
                "ResolveAwsEndpointV2" to resolveAwsEndpoint,
                "AwsEndpoint" to awsEndpoint
            )

        }
        val resolver = EndpointConfigCustomization(codegenContext, s3EndpointResolver)
        return removeDefaultEndpoint
    }
}

class S3(codegenContext: CodegenContext) : RestXml(codegenContext) {
    private val runtimeConfig = codegenContext.runtimeConfig
    private val errorScope = arrayOf(
        "Bytes" to RuntimeType.Bytes,
        "Error" to RuntimeType.GenericError(runtimeConfig),
        "HeaderMap" to RuntimeType.http.member("HeaderMap"),
        "Response" to RuntimeType.http.member("Response"),
        "XmlError" to CargoDependency.smithyXml(runtimeConfig).asType().member("decode::XmlError"),
        "base_errors" to restXmlErrors,
        "s3_errors" to AwsRuntimeType.S3Errors,
    )

    override fun parseHttpGenericError(operationShape: OperationShape): RuntimeType {
        return RuntimeType.forInlineFun("parse_http_generic_error", RustModule.private("xml_deser")) {
            it.rustBlockTemplate(
                "pub fn parse_http_generic_error(response: &#{Response}<#{Bytes}>) -> Result<#{Error}, #{XmlError}>",
                *errorScope
            ) {
                rustTemplate(
                    """
                    if response.body().is_empty() {
                        let mut err = #{Error}::builder();
                        if response.status().as_u16() == 404 {
                            err.code("NotFound");
                        }
                        Ok(err.build())
                    } else {
                        let base_err = #{base_errors}::parse_generic_error(response.body().as_ref())?;
                        Ok(#{s3_errors}::parse_extended_error(base_err, response.headers()))
                    }
                    """,
                    *errorScope
                )
            }
        }
    }
}

class S3PubUse : LibRsCustomization() {
    override fun section(section: LibRsSection): Writable = when (section) {
        is LibRsSection.Body -> writable { rust("pub use #T::ErrorExt;", AwsRuntimeType.S3Errors) }
        else -> emptySection
    }
}
