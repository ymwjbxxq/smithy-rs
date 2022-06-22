/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.smithy.customize

import software.amazon.smithy.rust.codegen.rustlang.CargoDependency
import software.amazon.smithy.rust.codegen.rustlang.Writable
import software.amazon.smithy.rust.codegen.rustlang.rustTemplate
import software.amazon.smithy.rust.codegen.rustlang.writable
import software.amazon.smithy.rust.codegen.smithy.CodegenContext
import software.amazon.smithy.rust.codegen.smithy.RuntimeConfig
import software.amazon.smithy.rust.codegen.smithy.RuntimeType
import software.amazon.smithy.rust.codegen.smithy.generators.config.ConfigCustomization
import software.amazon.smithy.rust.codegen.smithy.generators.config.EventStreamSigningConfig

/**
 * The NoOpEventStreamSigningDecorator:
 * - adds a `new_event_stream_signer()` method to `config` to create an Event Stream NoOp signer
 * - can be customized by subclassing, see SigV4SigningDecorator
 */
open class NoOpEventStreamSigningDecorator : EventStreamDecorator {
    override val name: String = "EventStreamDecorator"
    override val order: Byte = 0

    override fun configCustomizations(
        codegenContext: CodegenContext,
        baseCustomizations: List<ConfigCustomization>
    ): List<ConfigCustomization> {
        if (!applies(codegenContext))
            return baseCustomizations
        return baseCustomizations + NoOpEventStreamSigningConfig(
            codegenContext.runtimeConfig,
        )
    }
}

class NoOpEventStreamSigningConfig(
    runtimeConfig: RuntimeConfig,
) : EventStreamSigningConfig(runtimeConfig) {
    private val smithyEventStream = CargoDependency.SmithyEventStream(runtimeConfig)
    private val codegenScope = arrayOf(
        "NoOpSigner" to RuntimeType("NoOpSigner", smithyEventStream, "aws_smithy_eventstream::frame"),
        "SharedPropertyBag" to RuntimeType(
            "SharedPropertyBag",
            CargoDependency.SmithyHttp(runtimeConfig),
            "aws_smithy_http::property_bag"
        )
    )

    override fun inner(): Writable {
        return writable {
            rustTemplate(
                """
                /// Creates a new Event Stream `SignMessage` implementor.
                pub fn new_event_stream_no_op_signer(
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
}
