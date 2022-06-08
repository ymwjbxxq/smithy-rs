// use aws_http::content_encoding::{
//     header_value::AWS_CHUNKED, AwsChunkedBody, AwsChunkedBodyOptions,
// };
// use aws_smithy_checksums::body::{ChecksumBody, ChecksumValidatedBody};
// use aws_smithy_checksums::{
//     checksum_header_name_to_checksum_algorithm, CHECKSUM_HEADERS_IN_PRIORITY_ORDER,
// };
// use aws_smithy_http::body::SdkBody;
// use bytes::Bytes;
// use http::request::{self, Request};
// use http_body::Body;

/// Given an `http::request::Builder`, `SdkBody`, and a checksum algorithm name, return a
/// `Request<SdkBody>` with checksum trailers where the content is `aws-chunked` encoded.
pub fn build_checksum_validated_request(
    request_builder: http::request::Builder,
    body: aws_smithy_http::body::SdkBody,
    checksum_algorithm: &str,
) -> http::Request<aws_smithy_http::body::SdkBody> {
    use http_body::Body;

    let original_body_size = body
        .size_hint()
        .exact()
        .expect("body must be sized if checksum is requested");
    let body = aws_smithy_checksums::body::ChecksumBody::new(checksum_algorithm, body);
    let checksum_trailer_name = body.trailer_name();
    let aws_chunked_body_options = aws_http::content_encoding::AwsChunkedBodyOptions::new()
        .with_stream_length(original_body_size as usize)
        .with_trailer_len(body.trailer_length() as usize);

    let body = aws_http::content_encoding::AwsChunkedBody::new(body, aws_chunked_body_options);
    let encoded_content_length = body
        .size_hint()
        .exact()
        .expect("encoded_length must return known size");
    let request_builder = request_builder
        .header(
            http::header::CONTENT_LENGTH,
            http::HeaderValue::from(encoded_content_length),
        )
        .header(
            http::header::HeaderName::from_static("x-amz-decoded-content-length"),
            http::HeaderValue::from(original_body_size),
        )
        .header(
            http::header::HeaderName::from_static("x-amz-trailer"),
            checksum_trailer_name,
        )
        .header(
            http::header::CONTENT_ENCODING,
            aws_http::content_encoding::header_value::AWS_CHUNKED.as_bytes(),
        );

    let body = aws_smithy_http::body::SdkBody::from_dyn(http_body::combinators::BoxBody::new(body));

    request_builder.body(body).expect("should be valid request")
}

/// Given a `Response<SdkBody>`, checksum algorithm name, and pre-calculated checksum, return a
/// `Response<SdkBody>` where the body will processed with the checksum algorithm and checked
/// against the pre-calculated checksum.
pub fn build_checksum_validated_sdk_body(
    body: aws_smithy_http::body::SdkBody,
    checksum_algorithm: &str,
    precalculated_checksum: bytes::Bytes,
) -> aws_smithy_http::body::SdkBody {
    let body = aws_smithy_checksums::body::ChecksumValidatedBody::new(
        body,
        checksum_algorithm,
        precalculated_checksum.clone(),
    );
    aws_smithy_http::body::SdkBody::from_dyn(http_body::combinators::BoxBody::new(body))
}

/// Given the name of a checksum algorithm and a `HeaderMap`, extract the checksum value from the
/// corresponding header as `Some(Bytes)`. If the header is unset, return `None`.
pub fn check_headers_for_precalculated_checksum(
    headers: &http::HeaderMap<http::HeaderValue>,
) -> Option<(&'static str, bytes::Bytes)> {
    for header_name in aws_smithy_checksums::CHECKSUM_HEADERS_IN_PRIORITY_ORDER {
        if let Some(precalculated_checksum) = headers.get(&header_name) {
            let checksum_algorithm =
                aws_smithy_checksums::checksum_header_name_to_checksum_algorithm(&header_name);
            let precalculated_checksum =
                bytes::Bytes::copy_from_slice(precalculated_checksum.as_bytes());

            return Some((checksum_algorithm, precalculated_checksum));
        }
    }

    None
}

// pub fn deser_payload_get_object_get_object_output_body(
//     body: &mut aws_smithy_http::body::SdkBody,
// ) -> std::result::Result<aws_smithy_http::byte_stream::ByteStream, crate::error::GetObjectError> {
//     // replace the body with an empty body
//     let body = std::mem::replace(body, aws_smithy_http::body::SdkBody::taken());
//     Ok(aws_smithy_http::byte_stream::ByteStream::new(body))
// }
