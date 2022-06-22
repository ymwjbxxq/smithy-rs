/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.smithy.customize

import software.amazon.smithy.model.shapes.OperationShape
import software.amazon.smithy.rust.codegen.smithy.CodegenContext
import software.amazon.smithy.rust.codegen.smithy.generators.config.ConfigCustomization

open class MergedEventStreamDecorator(
    private val decorators: List<EventStreamDecorator>
) : RustCodegenDecorator {
    override val name: String = "MergedEventStreamDecorator"
    override val order: Byte = 0

    override fun configCustomizations(
        codegenContext: CodegenContext,
        baseCustomizations: List<ConfigCustomization>
    ): List<ConfigCustomization> {
        return baseCustomizations + decorators
            .filter { it.applies(codegenContext) }
            .flatMap { it.configCustomizations(codegenContext, baseCustomizations) }
    }

    override fun operationCustomizations(
        codegenContext: CodegenContext,
        operation: OperationShape,
        baseCustomizations: List<OperationCustomization>
    ): List<OperationCustomization> {
        return baseCustomizations + decorators
            .filter { it.applies(codegenContext) }
            .flatMap { it.operationCustomizations(codegenContext, operation, baseCustomizations) }
    }
}
