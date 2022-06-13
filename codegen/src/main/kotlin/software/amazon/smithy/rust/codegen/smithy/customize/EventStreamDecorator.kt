/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.smithy.customize

import software.amazon.smithy.model.shapes.OperationShape
import software.amazon.smithy.rust.codegen.rustlang.CargoDependency
import software.amazon.smithy.rust.codegen.rustlang.Writable
import software.amazon.smithy.rust.codegen.rustlang.rustTemplate
import software.amazon.smithy.rust.codegen.rustlang.writable
import software.amazon.smithy.rust.codegen.smithy.CodegenContext
import software.amazon.smithy.rust.codegen.smithy.RuntimeConfig
import software.amazon.smithy.rust.codegen.smithy.RuntimeType
import software.amazon.smithy.rust.codegen.smithy.generators.config.ConfigCustomization
import software.amazon.smithy.rust.codegen.smithy.generators.config.ServiceConfig
import software.amazon.smithy.rust.codegen.util.hasEventStreamOperations

/**
 * The EventStreamDecorator:
 * - adds a `new_event_stream_signer()` method to `config` to create an Event Stream NoOp signer
 * - can be customized by subclassing, see SigV4SigningDecorator
 */
open class EventStreamDecorator(
    private val decorators: List<EventStreamDecorator>
) : RustCodegenDecorator {
    override val name: String = "EventStreamDecorator"
    override val order: Byte = 0

    open fun applies(codegenContext: CodegenContext): Boolean = true

    override fun configCustomizations(
        codegenContext: CodegenContext,
        baseCustomizations: List<ConfigCustomization>
    ): List<ConfigCustomization> {
        decorators.forEach {
            if (it.applies(codegenContext)) return it.configCustomizations(codegenContext, baseCustomizations)
        }
        return baseCustomizations + EventStreamSignConfig(
            codegenContext.runtimeConfig,
            codegenContext.serviceShape.hasEventStreamOperations(codegenContext.model),
        )
    }

    override fun operationCustomizations(
        codegenContext: CodegenContext,
        operation: OperationShape,
        baseCustomizations: List<OperationCustomization>
    ): List<OperationCustomization> {
        decorators.forEach {
            if (it.applies(codegenContext)) return it.operationCustomizations(codegenContext, operation, baseCustomizations)
        }
        return baseCustomizations
    }
}

class EventStreamSignConfig(
    runtimeConfig: RuntimeConfig,
    private val serviceHasEventStream: Boolean,
) : ConfigCustomization() {
    private val smithyEventStream = CargoDependency.SmithyEventStream(runtimeConfig)
    private val codegenScope = arrayOf(
        "NoOpSigner" to RuntimeType("NoOpSigner", smithyEventStream, "aws_smithy_eventstream::frame"),
        "SharedPropertyBag" to RuntimeType(
            "SharedPropertyBag",
            CargoDependency.SmithyHttp(runtimeConfig),
            "aws_smithy_http::property_bag"
        )
    )

    override fun section(section: ServiceConfig): Writable {
        return when (section) {
            is ServiceConfig.ConfigImpl -> writable {
                if (serviceHasEventStream) {
                    rustTemplate(
                        """
                        /// Creates a new Event Stream `SignMessage` implementor.
                        pub fn new_event_stream_signer(
                            &self,
                            _properties: #{SharedPropertyBag}
                        ) -> #{NoOpSigner} {
                            #{NoOpSigner}{}
                        }
                        """,
                        *codegenScope
                    )
                }
            }
            else -> emptySection
        }
    }
}
