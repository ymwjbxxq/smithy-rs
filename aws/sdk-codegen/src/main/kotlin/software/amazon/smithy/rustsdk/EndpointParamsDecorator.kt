package software.amazon.smithy.rustsdk

import software.amazon.smithy.rust.codegen.rustlang.Writable
import software.amazon.smithy.rust.codegen.rustlang.writable
import software.amazon.smithy.rust.codegen.smithy.CodegenContext
import software.amazon.smithy.rust.codegen.smithy.customize.OperationCustomization
import software.amazon.smithy.rust.codegen.smithy.customize.OperationSection
import software.amazon.smithy.rust.codegen.smithy.customize.RustCodegenDecorator

class EndpointParamsDecorator: RustCodegenDecorator {
}

class InjectEndpointParams(val ctx: CodegenContext): OperationCustomization() {
    override fun section(section: OperationSection): Writable {
        when(section) {
            is OperationSection.MutateRequest -> writable {
                ctx.endpointsGenerator()

            }
            else -> emptySection
        }
    }
}