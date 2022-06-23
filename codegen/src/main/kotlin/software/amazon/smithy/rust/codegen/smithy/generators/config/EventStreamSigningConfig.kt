/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.smithy.generators.config

import software.amazon.smithy.rust.codegen.rustlang.Writable
import software.amazon.smithy.rust.codegen.smithy.RuntimeConfig

open class EventStreamSigningConfig(
    runtimeConfig: RuntimeConfig,
) : ConfigCustomization() {
    override fun section(section: ServiceConfig): Writable {
        return when (section) {
            is ServiceConfig.ConfigImpl -> inner()
            else -> emptySection
        }
    }

    open fun inner(): Writable {
        return emptySection
    }
}
