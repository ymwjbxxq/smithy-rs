/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.smithy.customizations

import org.junit.jupiter.api.Test
import software.amazon.smithy.rust.codegen.smithy.RustCodegenPlugin
import software.amazon.smithy.rust.codegen.testutil.asSmithyModel
import software.amazon.smithy.rust.codegen.testutil.generatePluginContext
import software.amazon.smithy.rust.codegen.util.runCommand

internal class HttpRequestChecksumGeneratorTest {
    private val model = """
        namespace test
        use aws.protocols#awsJson1_1
        use aws.protocols#httpChecksum

        @awsJson1_1
        service TestService {
            operations: [PutSomething]
        }

        @httpChecksum(
            requestChecksumRequired: true,
            requestAlgorithmMember: "checksumAlgorithm",
            requestValidationModeMember: "validationMode",
            responseAlgorithms: ["CRC32C", "CRC32", "SHA1", "SHA256"]
        )
        operation PutSomething {
            input: PutSomethingInput,
        }

        structure PutSomethingInput {
            @httpHeader("x-amz-request-algorithm")
            checksumAlgorithm: ChecksumAlgorithm,

            @httpHeader("x-amz-response-validation-mode")
            validationMode: ValidationMode,

            @httpPayload
            content: Blob,
        }

        @enum([
            {
                value: "CRC32C",
                name: "CRC32C"
            },
            {
                value: "CRC32",
                name: "CRC32"
            },
            {
                value: "SHA1",
                name: "SHA1"
            },
            {
                value: "SHA256",
                name: "SHA256"
            }
        ])
        string ChecksumAlgorithm

        @enum([
            {
                value: "ENABLED",
                name: "ENABLED"
            }
        ])
        string ValidationMode
    """.asSmithyModel()

    private val streamingModel = """
        namespace test
        use aws.protocols#awsJson1_1
        use aws.protocols#httpChecksum

        @awsJson1_1
        service TestService {
            operations: [PutSomething]
        }

        @httpChecksum(
            requestChecksumRequired: true,
            requestAlgorithmMember: "checksumAlgorithm",
            requestValidationModeMember: "validationMode",
            responseAlgorithms: ["CRC32C", "CRC32", "SHA1", "SHA256"]
        )
        operation PutSomething {
            input: PutSomethingInput,
        }

        structure PutSomethingInput {
            @httpHeader("x-amz-request-algorithm")
            checksumAlgorithm: ChecksumAlgorithm,

            @httpHeader("x-amz-response-validation-mode")
            validationMode: ValidationMode,

            @httpPayload
            content: StreamingBlob,
        }

        @streaming
        blob StreamingBlob

        @enum([
            {
                value: "CRC32C",
                name: "CRC32C"
            },
            {
                value: "CRC32",
                name: "CRC32"
            },
            {
                value: "SHA1",
                name: "SHA1"
            },
            {
                value: "SHA256",
                name: "SHA256"
            }
        ])
        string ChecksumAlgorithm

        @enum([
            {
                value: "ENABLED",
                name: "ENABLED"
            }
        ])
        string ValidationMode
    """.asSmithyModel()

    @Test
    fun `generate checksum stuff that compiles`() {
        val (ctx, testDir) = generatePluginContext(model)
        RustCodegenPlugin().execute(ctx)
        println("codegen has finished, build artifacts are located in $testDir")
        "cargo test".runCommand(testDir)
    }

    @Test
    fun `generate checksum stuff that compiles (streaming)`() {
        val (ctx, testDir) = generatePluginContext(model)
        RustCodegenPlugin().execute(ctx)
        println("codegen has finished, build artifacts are located in $testDir")
        "cargo test".runCommand(testDir)
    }
}
