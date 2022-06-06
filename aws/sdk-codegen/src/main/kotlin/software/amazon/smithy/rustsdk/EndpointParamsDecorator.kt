package software.amazon.smithy.rustsdk

import software.amazon.smithy.aws.reterminus.EndpointIndex
import software.amazon.smithy.aws.reterminus.lang.parameters.Builtins
import software.amazon.smithy.aws.reterminus.lang.parameters.Parameter
import software.amazon.smithy.model.shapes.OperationShape
import software.amazon.smithy.model.shapes.StructureShape
import software.amazon.smithy.rust.codegen.rustlang.Writable
import software.amazon.smithy.rust.codegen.rustlang.rust
import software.amazon.smithy.rust.codegen.rustlang.rustTemplate
import software.amazon.smithy.rust.codegen.rustlang.writable
import software.amazon.smithy.rust.codegen.smithy.CodegenContext
import software.amazon.smithy.rust.codegen.smithy.customize.OperationCustomization
import software.amazon.smithy.rust.codegen.smithy.customize.OperationSection
import software.amazon.smithy.rust.codegen.smithy.customize.RustCodegenDecorator
import software.amazon.smithy.rust.codegen.util.orNull
import software.amazon.smithy.rust.codegen.util.toSnakeCase

class EndpointParamsDecorator : RustCodegenDecorator {
    override val name: String = "EndpointParamsInjector"
    override val order: Byte = 0
    override fun operationCustomizations(
        codegenContext: CodegenContext,
        operation: OperationShape,
        baseCustomizations: List<OperationCustomization>
    ): List<OperationCustomization> {
        return baseCustomizations + InjectEndpointParams(codegenContext, operation)
    }
}

class InjectEndpointParams(private val ctx: CodegenContext, private val operationShape: OperationShape) :
    OperationCustomization() {
    val idx = EndpointIndex.of(ctx.model)
    override fun section(section: OperationSection): Writable {
        return when (section) {
            is OperationSection.MutateInput -> writable {
                rustTemplate(
                    """
                        let params = #{Params}::builder()
                            #{builder:W};
                    """,
                    "Params" to ctx.endpointsGenerator().paramsType(),
                    "builder" to builderFields(section)
                )
            }
            is OperationSection.MutateRequest -> writable {
                rust("${section.request}.properties_mut().insert(params);")
            }
            else -> emptySection
        }
    }

    private fun builderFields(section: OperationSection.MutateInput) = writable {
        val memberParams = idx.getOperationMemberBindings(operationShape).orNull() ?: mapOf()
        val builtInParams = ctx.endpointsGenerator().params().toList().filter { it.isBuiltIn }
        memberParams.forEach { (memberShape, param) ->
            rust(".set_${param.name.toSnakeCase()}(${section.input}.${ctx.symbolProvider.toMemberName(memberShape)}.as_ref())")
        }
        builtInParams.forEach { param ->
            when {
                param == Builtins.REGION -> rust(".set_region(${section.config}.region.as_ref().map(|r|r.as_ref()))")
                else -> rust("/* ignored param: $param */")
            }
        }
        rust(".build()")

    }
}