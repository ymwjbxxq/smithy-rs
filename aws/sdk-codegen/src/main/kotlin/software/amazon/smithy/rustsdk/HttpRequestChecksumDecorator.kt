/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0.
 */

package software.amazon.smithy.rustsdk

import software.amazon.smithy.aws.traits.HttpChecksumTrait
import software.amazon.smithy.model.shapes.OperationShape
import software.amazon.smithy.rust.codegen.rustlang.CargoDependency
import software.amazon.smithy.rust.codegen.rustlang.Writable
import software.amazon.smithy.rust.codegen.rustlang.asType
import software.amazon.smithy.rust.codegen.rustlang.rust
import software.amazon.smithy.rust.codegen.rustlang.rustTemplate
import software.amazon.smithy.rust.codegen.rustlang.writable
import software.amazon.smithy.rust.codegen.smithy.CodegenContext
import software.amazon.smithy.rust.codegen.smithy.RuntimeConfig
import software.amazon.smithy.rust.codegen.smithy.RuntimeType
import software.amazon.smithy.rust.codegen.smithy.customize.OperationCustomization
import software.amazon.smithy.rust.codegen.smithy.customize.OperationSection
import software.amazon.smithy.rust.codegen.smithy.customize.RustCodegenDecorator
import software.amazon.smithy.rust.codegen.smithy.generators.operationBuildError
import software.amazon.smithy.rust.codegen.util.expectMember
import software.amazon.smithy.rust.codegen.util.getTrait
import software.amazon.smithy.rust.codegen.util.hasStreamingMember
import software.amazon.smithy.rust.codegen.util.inputShape
import software.amazon.smithy.rust.codegen.util.orNull

private fun RuntimeConfig.checksumsRuntimeCrate(): RuntimeType =
    RuntimeType(null, this.runtimeCrate("checksums"), "aws_smithy_checksums")
private fun RuntimeConfig.httpHeaderRuntimeCrate(): RuntimeType =
    RuntimeType("header", this.runtimeCrate("http"), "aws_smithy_http")

class HttpRequestChecksumDecorator : RustCodegenDecorator {
    override val name: String = "HttpRequestChecksum"
    override val order: Byte = 0

    override fun operationCustomizations(
        codegenContext: CodegenContext,
        operation: OperationShape,
        baseCustomizations: List<OperationCustomization>
    ): List<OperationCustomization> {
        return baseCustomizations + HttpRequestChecksumCustomization(codegenContext, operation)
    }
}

// This generator was implemented based on this spec:
// https://awslabs.github.io/smithy/1.0/spec/aws/aws-core.html#http-request-checksums
class HttpRequestChecksumCustomization(
    private val codegenContext: CodegenContext,
    private val operationShape: OperationShape
) : OperationCustomization() {
    private val runtimeConfig = codegenContext.runtimeConfig
    private val codegenScope = arrayOf(
        "http" to CargoDependency.Http.asType(),
        "md5" to CargoDependency.Md5.asType(),
        "base64_encode" to RuntimeType.Base64Encode(codegenContext.runtimeConfig),
        "BuildError" to runtimeConfig.operationBuildError(),
        "str_to_body_callback" to runtimeConfig.checksumsRuntimeCrate().member("str_to_body_callback"),
        "str_to_header_value" to runtimeConfig.checksumsRuntimeCrate().member("str_to_header_value"),
        "calculate_streaming_body_trailer_chunk_size" to runtimeConfig.httpHeaderRuntimeCrate().member("calculate_streaming_body_trailer_chunk_size"),
        "append_merge_header_maps" to runtimeConfig.httpHeaderRuntimeCrate().member("append_merge_header_maps"),
        "Md5Callback" to runtimeConfig.checksumsRuntimeCrate().member("Md5Callback"),
        "sig_auth" to runtimeConfig.awsRuntimeDependency("aws-sig-auth").asType()
    )

    private fun md5Checksum() = writable {
        rustTemplate(
            """
            let checksum = #{md5}::compute(data);
            req.headers_mut().insert(
                #{http}::header::HeaderName::from_static("content-md5"),
                #{base64_encode}(&checksum[..]).parse().expect("checksum is valid header value")
            );
            """,
            *codegenScope
        )
    }

    private fun flexibleChecksum(checksumAlgorithm: String?) = writable {
        if (checksumAlgorithm != null) {
            val checksumAlgorithmMemberShape =
                operationShape.inputShape(codegenContext.model).expectMember(checksumAlgorithm)
            val requestAlgorithmMember = codegenContext.symbolProvider.toMemberName(checksumAlgorithmMemberShape)
            rustTemplate(
                """
                let mut callback_headers = None;
                if let Some(checksum_algorithm) = $requestAlgorithmMember {
                    let mut callback = #{str_to_body_callback}(checksum_algorithm.as_str());
                    callback.update(&data).map_err(|err| #{BuildError}::Other(err))?;
                    callback_headers = callback.trailers().map_err(|err| #{BuildError}::Other(err))?;
                }
                """,
                *codegenScope
            )
        }
    }

    private fun streamingCallback(isRequired: Boolean, checksumAlgorithm: String?) = writable {
        if (isRequired) {
            if (checksumAlgorithm == null) {
                // We need a checksum but users can't set one, return an MD5 checksum callback
                rustTemplate("let checksum_callback = Some(#{Md5Callback}::default());", *codegenScope)
            } else {
                val checksumAlgorithmMemberShape =
                    operationShape.inputShape(codegenContext.model).expectMember(checksumAlgorithm)
                val requestAlgorithmMember = codegenContext.symbolProvider.toMemberName(checksumAlgorithmMemberShape)
                // We need a checksum and users MAY set one, use that one with MD5 as a fallback
                rustTemplate(
                    """
                    // Create a checksum callback if the user requested one or fall back to an MD5 checksum callback
                    let checksum_callback = $requestAlgorithmMember.map(|checksum_algorithm| {
                        #{str_to_body_callback}(checksum_algorithm.as_str())
                    }).or_else(|| Some(#{Md5Callback}::default()));
                    """,
                    *codegenScope
                )
            }
        } else {
            if (checksumAlgorithm != null) {
                val checksumAlgorithmMemberShape =
                    operationShape.inputShape(codegenContext.model).expectMember(checksumAlgorithm)
                val requestAlgorithmMember = codegenContext.symbolProvider.toMemberName(checksumAlgorithmMemberShape)
                // Users have the option to set a checksum, but it isn't required
                rustTemplate(
                    """
                    // Create a checksum callback if the user requested one
                    let checksum_callback = $requestAlgorithmMember.as_ref().map(|checksum_algorithm| {
                        #{str_to_body_callback}(checksum_algorithm.as_str())
                    });
                    """,
                    *codegenScope
                )
            }
        }
    }

    // When appending trailers to a streaming callback, we must also set a header notifying the service
    // that we're sending a checksum trailer.
    private fun streamingCallbackHeader(isRequired: Boolean, checksumAlgorithm: String?) = writable {
        if (isRequired) {
            if (checksumAlgorithm == null) {
                rustTemplate(
                    """
                    let header_value = #{http}::header::HeaderValue::from_static("content-md5");
                    """,
                    *codegenScope
                )
            } else {
                val checksumAlgorithmMemberShape =
                    operationShape.inputShape(codegenContext.model).expectMember(checksumAlgorithm)
                val requestAlgorithmMember = codegenContext.symbolProvider.toMemberName(checksumAlgorithmMemberShape)
                rustTemplate(
                    """
                    let header_value = match $requestAlgorithmMember {
                        Some(checksum_algorithm) => #{str_to_header_value}(checksum_algorithm),
                        None => #{http}::header::HeaderValue::from_static("content-md5"),
                    }
                    """,
                    *codegenScope
                )
            }
        } else {
            if (checksumAlgorithm != null) {
                val checksumAlgorithmMemberShape =
                    operationShape.inputShape(codegenContext.model).expectMember(checksumAlgorithm)
                val requestAlgorithmMember = codegenContext.symbolProvider.toMemberName(checksumAlgorithmMemberShape)
                rustTemplate(
                    """
                    let header_value = #{str_to_header_value}($requestAlgorithmMember.as_ref().unwrap().as_str());
                    """,
                    *codegenScope
                )
            }
        }

        rustTemplate(
            """
            req.headers_mut().append(
                #{http}::header::HeaderName::from_static("x-amz-trailer"),
                header_value,
            );
            req.headers_mut().append(
                #{http}::header::HeaderName::from_static("content-encoding"),
                #{http}::header::HeaderValue::from_static("aws-chunked"),
            );

            // In practice, this will always exist
            if let Some(content_length) = req.headers().get(
                #{http}::header::HeaderName::from_static("content-length")
            ).cloned() {
                req.headers_mut().insert(
                    #{http}::header::HeaderName::from_static("x-amz-decoded-content-length"),
                    content_length,
                );
                req.headers_mut().insert(
                    #{http}::header::HeaderName::from_static("transfer-encoding"),
                    #{http}::header::HeaderValue::from_static("chunked"),
                );
                let _ = req.headers_mut().remove(
                    #{http}::header::HeaderName::from_static("content-length"),
                );
            }
            """,
            *codegenScope
        )
    }

    private fun mergeHeaders(isRequired: Boolean, checksumAlgorithm: String?) = writable {
        if (isRequired) {
            if (checksumAlgorithm == null) {
                // We need a checksum but users can't set one, calculate an MD5 checksum
                rustTemplate("#{md5_checksum:W}", "md5_checksum" to md5Checksum())
            } else {
                // We need a checksum and users MAY set one, use that one with MD5 as a fallback
                rustTemplate(
                    """
                    if let Some(callback_headers) = callback_headers {
                        // Take headers from the callback and append them to the request headers
                        #{append_merge_header_maps}(req.headers_mut(), callback_headers);
                    } else {
                        // Checksums are required for this request, fall back to MD5
                        #{md5_checksum:W}
                    }
                    """,
                    *codegenScope,
                    "md5_checksum" to md5Checksum()
                )
            }
        } else {
            // Users have the option to set a checksum, but it isn't required
            rustTemplate(
                """
                // Take any headers from the callback and append them to the request headers
                if let Some(callback_headers) = callback_headers {
                    #{append_merge_header_maps}(req.headers_mut(), callback_headers);
                }
                """,
                *codegenScope
            )
        }
    }

    override fun section(section: OperationSection): Writable {
        // Get the `HttpChecksumTrait`, returning early if this `OperationShape` doesn't have one
        val checksumTrait = operationShape.getTrait<HttpChecksumTrait>() ?: return emptySection
        val checksumAlgorithm = checksumTrait.requestAlgorithmMember.orNull()

        // Various other things will consume the input struct before we can get at the checksum algorithm
        // field within it. This ensures that we preserve a copy of it. It's an enum so cloning is cheap.
        if (section is OperationSection.MutateInput && checksumAlgorithm != null) return {
            val checksumAlgorithmMemberShape =
                operationShape.inputShape(codegenContext.model).expectMember(checksumAlgorithm)
            val requestAlgorithmMember = codegenContext.symbolProvider.toMemberName(checksumAlgorithmMemberShape)
            rust("let $requestAlgorithmMember = self.$requestAlgorithmMember().map(|$requestAlgorithmMember| $requestAlgorithmMember.clone());")
        }

        if (section !is OperationSection.MutateRequest) return emptySection

        // Return if a request checksum is not required and there's no way to set one
        // This happens when an operation only supports response checksums
        if (!checksumTrait.isRequestChecksumRequired && checksumAlgorithm == null) {
            return emptySection
        }

        if (operationShape.inputShape(codegenContext.model).hasStreamingMember(codegenContext.model)) {
            return {
                rustTemplate(
                    """
                    ${section.request} = ${section.request}.augment(|mut req, properties| {
                        #{streaming_callback:W}

                        if let Some(callback) = checksum_callback {
                            properties.insert(#{sig_auth}::signer::SignableBody::StreamingUnsignedPayloadTrailer);

                            #{streaming_callback_header:W}
                            req.body_mut().with_callback(callback);
                        }

                        Result::<_, #{BuildError}>::Ok(req)
                    })?;
                    """,
                    *codegenScope,
                    "streaming_callback" to streamingCallback(checksumTrait.isRequestChecksumRequired, checksumAlgorithm),
                    "streaming_callback_header" to streamingCallbackHeader(checksumTrait.isRequestChecksumRequired, checksumAlgorithm),
                )
            }
        }

        return {
            rustTemplate(
                """
                ${section.request} = ${section.request}.augment(|mut req, _| {
                    let data = req
                        .body()
                        .bytes()
                        .expect("checksum can only be computed for non-streaming operations");

                    #{flexible_checksum:W}
                    #{merge_headers:W}

                    Result::<_, #{BuildError}>::Ok(req)
                })?;
                """,
                *codegenScope,
                "flexible_checksum" to flexibleChecksum(checksumAlgorithm),
                "merge_headers" to mergeHeaders(checksumTrait.isRequestChecksumRequired, checksumAlgorithm),
            )
        }
    }
}

// "com.amazonaws.s3#PutObject": {
//    "type": "operation",
//    "input": {
//        "target": "com.amazonaws.s3#PutObjectRequest"
//    },
//    "output": {
//        "target": "com.amazonaws.s3#PutObjectOutput"
//    },
//    "traits": {
//        "aws.protocols#httpChecksum": {
//        "requestAlgorithmMember": "ChecksumAlgorithm"
//    },
//        "smithy.api#documentation": "<p>Adds an object to a bucket. You must have WRITE permissions on a bucket to add an object\n         to it.</p>\n\n\n         <p>Amazon S3 never adds partial objects; if you receive a success response, Amazon S3 added the\n         entire object to the bucket.</p>\n\n         <p>Amazon S3 is a distributed system. If it receives multiple write requests for the same object\n         simultaneously, it overwrites all but the last object written. Amazon S3 does not provide object\n         locking; if you need this, make sure to build it into your application layer or use\n         versioning instead.</p>\n\n         <p>To ensure that data is not corrupted traversing the network, use the\n            <code>Content-MD5</code> header. When you use this header, Amazon S3 checks the object\n         against the provided MD5 value and, if they do not match, returns an error. Additionally,\n         you can calculate the MD5 while putting an object to Amazon S3 and compare the returned ETag to\n         the calculated MD5 value.</p>\n         <note>\n            <ul>\n               <li>\n                  <p>To successfully complete the <code>PutObject</code> request, you must have the \n               <code>s3:PutObject</code> in your IAM permissions.</p>\n               </li>\n               <li>\n                  <p>To successfully change the objects acl of your <code>PutObject</code> request, \n               you must have the <code>s3:PutObjectAcl</code> in your IAM permissions.</p>\n               </li>\n               <li>\n                  <p> The <code>Content-MD5</code> header is required for any request to upload an object\n                  with a retention period configured using Amazon S3 Object Lock. For more information about\n                  Amazon S3 Object Lock, see <a href=\"https://docs.aws.amazon.com/AmazonS3/latest/dev/object-lock-overview.html\">Amazon S3 Object Lock Overview</a>\n                  in the <i>Amazon S3 User Guide</i>. </p>\n               </li>\n            </ul>\n         </note>\n         <p>\n            <b>Server-side Encryption</b>\n         </p>\n         <p>You can optionally request server-side encryption. With server-side encryption, Amazon S3 encrypts \n         your data as it writes it to disks in its data centers and decrypts the data\n         when you access it. You have the option to provide your own encryption key or use Amazon Web Services\n         managed encryption keys (SSE-S3 or SSE-KMS). For more information, see <a href=\"https://docs.aws.amazon.com/AmazonS3/latest/dev/UsingServerSideEncryption.html\">Using Server-Side\n            Encryption</a>.</p>\n         <p>If you request server-side encryption using Amazon Web Services Key Management Service (SSE-KMS), you can enable \n         an S3 Bucket Key at the object-level. For more information, see <a href=\"https://docs.aws.amazon.com/AmazonS3/latest/dev/bucket-key.html\">Amazon S3 Bucket Keys</a> in the \n         <i>Amazon S3 User Guide</i>.</p>\n         <p>\n            <b>Access Control List (ACL)-Specific Request\n         Headers</b>\n         </p>\n         <p>You can use headers to grant ACL- based permissions. By default, all objects are\n         private. Only the owner has full access control. When adding a new object, you can grant\n         permissions to individual Amazon Web Services accounts or to predefined groups defined by Amazon S3. These\n         permissions are then added to the ACL on the object. For more information, see <a href=\"https://docs.aws.amazon.com/AmazonS3/latest/dev/acl-overview.html\">Access Control List\n            (ACL) Overview</a> and <a href=\"https://docs.aws.amazon.com/AmazonS3/latest/dev/acl-using-rest-api.html\">Managing ACLs Using the REST\n            API</a>. </p>\n         <p>If the bucket that you're uploading objects to uses the bucket owner enforced setting\n         for S3 Object Ownership, ACLs are disabled and no longer affect permissions. Buckets that\n         use this setting only accept PUT requests that don't specify an ACL or PUT requests that\n         specify bucket owner full control ACLs, such as the <code>bucket-owner-full-control</code> canned\n         ACL or an equivalent form of this ACL expressed in the XML format. PUT requests that contain other\n         ACLs (for example, custom grants to certain Amazon Web Services accounts) fail and return a\n            <code>400</code> error with the error code\n         <code>AccessControlListNotSupported</code>.</p>\n         <p>For more information, see <a href=\"https://docs.aws.amazon.com/AmazonS3/latest/userguide/about-object-ownership.html\"> Controlling ownership of\n         objects and disabling ACLs</a> in the <i>Amazon S3 User Guide</i>.</p>\n         <note>\n            <p>If your bucket uses the bucket owner enforced setting for Object Ownership, \n            all objects written to the bucket by any account will be owned by the bucket owner.</p>\n         </note>\n         <p>\n            <b>Storage Class Options</b>\n         </p>\n         <p>By default, Amazon S3 uses the STANDARD Storage Class to store newly created objects. The\n         STANDARD storage class provides high durability and high availability. Depending on\n         performance needs, you can specify a different Storage Class. Amazon S3 on Outposts only uses\n         the OUTPOSTS Storage Class. For more information, see <a href=\"https://docs.aws.amazon.com/AmazonS3/latest/dev/storage-class-intro.html\">Storage Classes</a> in the\n         <i>Amazon S3 User Guide</i>.</p>\n\n\n         <p>\n            <b>Versioning</b>\n         </p>\n         <p>If you enable versioning for a bucket, Amazon S3 automatically generates a unique version ID\n         for the object being stored. Amazon S3 returns this ID in the response. When you enable\n         versioning for a bucket, if Amazon S3 receives multiple write requests for the same object\n         simultaneously, it stores all of the objects.</p>\n         <p>For more information about versioning, see <a href=\"https://docs.aws.amazon.com/AmazonS3/latest/dev/AddingObjectstoVersioningEnabledBuckets.html\">Adding Objects to\n            Versioning Enabled Buckets</a>. For information about returning the versioning state\n         of a bucket, see <a href=\"https://docs.aws.amazon.com/AmazonS3/latest/API/API_GetBucketVersioning.html\">GetBucketVersioning</a>. </p>\n\n\n         <p class=\"title\">\n            <b>Related Resources</b>\n         </p>\n         <ul>\n            <li>\n               <p>\n                  <a href=\"https://docs.aws.amazon.com/AmazonS3/latest/API/API_CopyObject.html\">CopyObject</a>\n               </p>\n            </li>\n            <li>\n               <p>\n                  <a href=\"https://docs.aws.amazon.com/AmazonS3/latest/API/API_DeleteObject.html\">DeleteObject</a>\n               </p>\n            </li>\n         </ul>",
//        "smithy.api#http": {
//        "method": "PUT",
//        "uri": "/{Bucket}/{Key+}?x-id=PutObject",
//        "code": 200
//    }
//    }
// },

// "com.amazonaws.s3#PutObjectRequest": {
//    "type": "structure",
//    "members": {
//        "ContentMD5": {
//            "target": "com.amazonaws.s3#ContentMD5",
//            "traits": {
//            "smithy.api#documentation": "<p>The base64-encoded 128-bit MD5 digest of the message (without the headers) according to\n         RFC 1864. This header can be used as a message integrity check to verify that the data is\n         the same data that was originally sent. Although it is optional, we recommend using the\n         Content-MD5 mechanism as an end-to-end integrity check. For more information about REST\n         request authentication, see <a href=\"https://docs.aws.amazon.com/AmazonS3/latest/dev/RESTAuthentication.html\">REST\n            Authentication</a>.</p>",
//            "smithy.api#httpHeader": "Content-MD5"
//        }
//        },
//        "ChecksumAlgorithm": {
//            "target": "com.amazonaws.s3#ChecksumAlgorithm",
//            "traits": {
//            "smithy.api#documentation": "<p>Indicates the algorithm used to create the checksum for the object when using the SDK. This header will not provide any\n    additional functionality if not using the SDK. When sending this header, there must be a corresponding <code>x-amz-checksum</code> or\n    <code>x-amz-trailer</code> header sent. Otherwise, Amazon S3 fails the request with the HTTP status code <code>400 Bad Request</code>. For more\n    information, see <a href=\"https://docs.aws.amazon.com/AmazonS3/latest/userguide/checking-object-integrity.html\">Checking object integrity</a> in\n    the <i>Amazon S3 User Guide</i>.</p>\n        <p>If you provide an individual checksum, Amazon S3 ignores any provided\n            <code>ChecksumAlgorithm</code> parameter.</p>",
//            "smithy.api#httpHeader": "x-amz-sdk-checksum-algorithm"
//        }
//        },
//        "ChecksumCRC32": {
//            "target": "com.amazonaws.s3#ChecksumCRC32",
//            "traits": {
//            "smithy.api#documentation": "<p>This header can be used as a data integrity check to verify that the data received is the same data that was originally sent.\n    This header specifies the base64-encoded, 32-bit CRC32 checksum of the object. For more information, see\n    <a href=\"https://docs.aws.amazon.com/AmazonS3/latest/userguide/checking-object-integrity.html\">Checking object integrity</a> in the\n    <i>Amazon S3 User Guide</i>.</p>",
//            "smithy.api#httpHeader": "x-amz-checksum-crc32"
//        }
//        },
//        "ChecksumCRC32C": {
//            "target": "com.amazonaws.s3#ChecksumCRC32C",
//            "traits": {
//            "smithy.api#documentation": "<p>This header can be used as a data integrity check to verify that the data received is the same data that was originally sent.\n    This header specifies the base64-encoded, 32-bit CRC32C checksum of the object. For more information, see\n    <a href=\"https://docs.aws.amazon.com/AmazonS3/latest/userguide/checking-object-integrity.html\">Checking object integrity</a> in the\n    <i>Amazon S3 User Guide</i>.</p>",
//            "smithy.api#httpHeader": "x-amz-checksum-crc32c"
//        }
//        },
//        "ChecksumSHA1": {
//            "target": "com.amazonaws.s3#ChecksumSHA1",
//            "traits": {
//            "smithy.api#documentation": "<p>This header can be used as a data integrity check to verify that the data received is the same data that was originally sent.\n    This header specifies the base64-encoded, 160-bit SHA-1 digest of the object. For more information, see\n    <a href=\"https://docs.aws.amazon.com/AmazonS3/latest/userguide/checking-object-integrity.html\">Checking object integrity</a> in the\n    <i>Amazon S3 User Guide</i>.</p>",
//            "smithy.api#httpHeader": "x-amz-checksum-sha1"
//        }
//        },
//        "ChecksumSHA256": {
//            "target": "com.amazonaws.s3#ChecksumSHA256",
//            "traits": {
//            "smithy.api#documentation": "<p>This header can be used as a data integrity check to verify that the data received is the same data that was originally sent.\n    This header specifies the base64-encoded, 256-bit SHA-256 digest of the object. For more information, see\n    <a href=\"https://docs.aws.amazon.com/AmazonS3/latest/userguide/checking-object-integrity.html\">Checking object integrity</a> in the\n    <i>Amazon S3 User Guide</i>.</p>",
//            "smithy.api#httpHeader": "x-amz-checksum-sha256"
//        }
//    }
// },
