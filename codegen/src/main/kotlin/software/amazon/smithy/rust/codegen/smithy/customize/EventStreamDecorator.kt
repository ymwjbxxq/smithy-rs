/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.smithy.customize

import software.amazon.smithy.rust.codegen.smithy.CodegenContext
import software.amazon.smithy.rust.codegen.util.hasEventStreamOperations

interface EventStreamDecorator : RustCodegenDecorator {
    fun applies(codegenContext: CodegenContext): Boolean =
        codegenContext.serviceShape.hasEventStreamOperations(codegenContext.model)
}
