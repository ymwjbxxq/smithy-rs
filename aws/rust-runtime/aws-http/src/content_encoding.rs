/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

use aws_smithy_checksums::body::ChecksumBody;
use aws_smithy_http::body::SdkBody;

use bytes::{Buf, Bytes, BytesMut};
use http::{HeaderMap, HeaderValue};
use http_body::{Body, SizeHint};
use pin_project::pin_project;

use std::pin::Pin;
use std::task::{Context, Poll};

const CRLF: &str = "\r\n";
const CHUNK_TERMINATOR: &str = "0\r\n";
// https://docs.aws.amazon.com/AmazonS3/latest/API/sigv4-streaming.html
const MINIMUM_CHUNK_LENGTH: usize = 1024 * 64;

/// Content encoding header value constants
pub mod header_value {
    /// Header value denoting "aws-chunked" encoding
    pub const AWS_CHUNKED: &str = "aws-chunked";
}

/// Options used when constructing an [`AwsChunkedBody`][AwsChunkedBody].
#[derive(Debug, Default)]
#[non_exhaustive]
pub struct AwsChunkedBodyOptions {
    /// The total size of the stream. For unsigned encoding this implies that
    /// there will only be a single chunk containing the underlying payload,
    /// unless ChunkLength is also specified.
    pub stream_length: Option<usize>,
    /// The maximum size of each chunk to be sent. Default value of 8KB.
    /// chunk_length must be at least 8KB.
    ///
    /// If ChunkLength and stream_length are both specified, the stream will be
    /// broken up into chunk_length chunks. The encoded length of the aws-chunked
    /// encoding can still be determined as long as all trailers, if any, have a
    /// fixed length.
    pub chunk_length: Option<usize>,
    /// The length of each trailer sent within an `AwsChunkedBody`. Necessary in
    /// order to correctly calculate the total size of the body accurately.
    pub trailer_lens: Vec<usize>,
}

impl AwsChunkedBodyOptions {
    /// Create a new [`AwsChunkedBodyOptions`][AwsChunkedBodyOptions]
    pub fn new() -> Self {
        Self::default()
    }

    /// Set stream length
    pub fn with_stream_length(mut self, stream_length: usize) -> Self {
        self.stream_length = Some(stream_length);
        self
    }

    /// Set chunk length
    pub fn with_chunk_length(mut self, chunk_length: usize) -> Self {
        self.chunk_length = Some(chunk_length);
        self
    }

    /// Set a trailer len
    pub fn with_trailer_len(mut self, trailer_len: usize) -> Self {
        self.trailer_lens.push(trailer_len);
        self
    }
}

/// A request body compatible with `Content-Encoding: aws-chunked`
#[derive(Debug)]
#[pin_project]
pub struct AwsChunkedBody<InnerBody> {
    #[pin]
    inner: InnerBody,
    already_wrote_chunk_size_prefix: bool,
    already_wrote_chunk_terminator: bool,
    already_wrote_trailers: bool,
    options: AwsChunkedBodyOptions,
}

// TODO make this work for any sized body
type Inner = ChecksumBody<SdkBody>;

impl AwsChunkedBody<Inner> {
    /// Wrap the given body in an outer body compatible with `Content-Encoding: aws-chunked`
    pub fn new(body: Inner, options: AwsChunkedBodyOptions) -> Self {
        Self {
            inner: body,
            already_wrote_chunk_size_prefix: false,
            already_wrote_chunk_terminator: false,
            already_wrote_trailers: false,
            options,
        }
    }

    fn encoded_length(&self) -> Option<usize> {
        if self.options.chunk_length.is_none() && self.options.stream_length.is_none() {
            return None;
        }

        let mut length = 0;
        let stream_length = self.options.stream_length.unwrap_or_default();
        if stream_length != 0 {
            if let Some(chunk_length) = self.options.chunk_length {
                // I don't think we'll hit this case b/c we only ever send things in one chunk
                assert!(chunk_length > MINIMUM_CHUNK_LENGTH);

                let num_chunks = stream_length / chunk_length;
                length += num_chunks * get_unsigned_chunk_bytes_length(chunk_length);
                let remainder = stream_length % chunk_length;
                if remainder != 0 {
                    length += get_unsigned_chunk_bytes_length(remainder);
                }
            } else {
                length += get_unsigned_chunk_bytes_length(stream_length);
            }
        }

        // End chunk
        length += CHUNK_TERMINATOR.len();

        // Trailers
        // TODO Figure out how to size the trailers, I think I need to not only know their lengths
        //      but also how many there are so that I can calculate the appropriate number of CRLFs.
        //      I think that we only do trailers with chunked encoding so it may be that
        //      `ChecksumBody` can take that into account and modify the size hint appropriately.
        for len in self.options.trailer_lens.iter() {
            length += len + CRLF.len();
        }

        // Encoding terminator
        length += CRLF.len();

        Some(length)
    }
}

fn prefix_with_total_chunk_size(data: Bytes, chunk_size: usize) -> Bytes {
    // Len is the size of the entire chunk as defined in `AwsChunkedBodyOptions`
    let mut prefixed_data = BytesMut::from(format!("{:X?}\r\n", chunk_size).as_bytes());
    prefixed_data.extend_from_slice(&data);

    prefixed_data.into()
}

fn get_unsigned_chunk_bytes_length(payload_length: usize) -> usize {
    let hex_repr_len = int_log16(payload_length) as usize;
    hex_repr_len + CRLF.len() + payload_length + CRLF.len()
}

fn trailers_as_aws_chunked_bytes(
    total_length_of_trailers_in_bytes: usize,
    trailer_map: Option<HeaderMap>,
) -> Bytes {
    use std::fmt::Write;

    // TODO the capacity for this should be known, figure it out and use `BytesMut::with_capacity`
    let mut trailers = String::with_capacity(total_length_of_trailers_in_bytes);
    let mut already_wrote_first_trailer = false;

    if let Some(trailer_map) = trailer_map {
        for (header_name, header_value) in trailer_map.into_iter() {
            match header_name {
                // New name, new value
                Some(header_name) => {
                    if already_wrote_first_trailer {
                        // First trailer shouldn't have a preceding CRLF, but every trailer after it should
                        trailers.write_str(CRLF).unwrap();
                    } else {
                        already_wrote_first_trailer = true;
                    }

                    trailers.write_str(header_name.as_str()).unwrap();
                    trailers.write_char(':').unwrap();
                }
                // Same name, new value
                None => {
                    trailers.write_char(',').unwrap();
                }
            }
            trailers.write_str(header_value.to_str().unwrap()).unwrap();
        }
    }

    // Write CRLF to end the body
    trailers.write_str(CRLF).unwrap();
    // If we wrote at least one trailer, we need to write an extra CRLF
    if total_length_of_trailers_in_bytes != 0 {
        trailers.write_str(CRLF).unwrap();
    }

    trailers.into()
}

impl Body for AwsChunkedBody<Inner> {
    type Data = Bytes;
    type Error = aws_smithy_http::body::Error;

    fn poll_data(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
    ) -> Poll<Option<Result<Self::Data, Self::Error>>> {
        tracing::info!("polling AwsChunkedBody");
        let this = self.project();
        if *this.already_wrote_trailers {
            return Poll::Ready(None);
        }

        if *this.already_wrote_chunk_terminator {
            return match this.inner.poll_trailers(cx) {
                Poll::Ready(Ok(trailers)) => {
                    *this.already_wrote_trailers = true;
                    let total_length_of_trailers_in_bytes = this.options.trailer_lens.iter().sum();

                    Poll::Ready(Some(Ok(trailers_as_aws_chunked_bytes(
                        total_length_of_trailers_in_bytes,
                        trailers,
                    ))))
                }
                Poll::Pending => Poll::Pending,
                Poll::Ready(Err(e)) => Poll::Ready(Some(Err(e))),
            };
        };

        match this.inner.poll_data(cx) {
            Poll::Ready(Some(Ok(mut data))) => {
                // A chunk must be prefixed by chunk size in hexadecimal
                let bytes = if *this.already_wrote_chunk_size_prefix {
                    tracing::info!("writing more of chunk");
                    data.copy_to_bytes(data.len())
                } else {
                    tracing::info!("writing initial part of chunk");

                    *this.already_wrote_chunk_size_prefix = true;
                    let total_chunk_size = this
                        .options
                        .chunk_length
                        .or(this.options.stream_length)
                        .unwrap_or_default();
                    prefix_with_total_chunk_size(data, total_chunk_size)
                };

                Poll::Ready(Some(Ok(bytes)))
            }
            Poll::Ready(None) => {
                tracing::info!("no more chunk data, writing terminator");
                *this.already_wrote_chunk_terminator = true;
                Poll::Ready(Some(Ok(Bytes::from("\r\n0\r\n"))))
            }
            Poll::Ready(Some(Err(e))) => Poll::Ready(Some(Err(e))),
            Poll::Pending => Poll::Pending,
        }
    }

    fn poll_trailers(
        self: Pin<&mut Self>,
        _cx: &mut Context<'_>,
    ) -> Poll<Result<Option<HeaderMap<HeaderValue>>, Self::Error>> {
        // When using aws-chunked content encoding, trailers have to be appended to the body
        Poll::Ready(Ok(None))
    }

    fn is_end_stream(&self) -> bool {
        // If we've written out the terminator, then we're done reading the body and can move on
        // to the trailers.
        // self.already_wrote_chunk_terminator
        false
    }

    fn size_hint(&self) -> SizeHint {
        SizeHint::with_exact(
            self.encoded_length()
                .expect("Requests made with aws-chunked encoding must have known size")
                as u64,
        )
    }
}

fn int_log16<T>(mut i: T) -> u64
where
    T: std::ops::DivAssign + PartialOrd + From<u8> + Copy,
{
    let mut len = 0;
    let zero = T::from(0);
    let sixteen = T::from(16);

    while i > zero {
        i /= sixteen;
        len += 1;
    }

    len
}

// // Chunked-Body    = *chunk
// //                   last-chunk
// //                   chunked-trailer
// //                   CRLF
// //
// // chunk           = chunk-size CRLF chunk-data CRLF
// // chunk-size      = 1*HEXDIG
// // last-chunk      = 1*("0") CRLF
// // chunked-trailer = *( entity-header CRLF )
// // entity-header   = field-name ":" OWS field-value OWS
// // For more info on what the abbreviations mean, see https://datatracker.ietf.org/doc/html/rfc7230#section-1.2
// pub fn content_length(&self) -> usize {
//     if self.content_encoding_is_aws_chunked {
//         let chunk = {
//             let chunk_data = self.body_length.unwrap_or_default();
//             let chunk_size = int_log16(chunk_data) as usize;
//             chunk_size + CRLF_LENGTH + chunk_data as usize + CRLF_LENGTH
//         };
//         let chunked_trailer: usize = self.trailer_lengths.iter().sum();
//
//         chunk + LAST_CHUNK_LENGTH + chunked_trailer + CRLF_LENGTH
//     } else {
//         self.body_length.unwrap_or_default() as usize
//     }
// }
//
// /// When sending streaming data to S3 with `content-encoding: aws-chunked`, it's necessary to set
// /// a `x-amz-decoded-content-length` header. This method will provide the value for that header.
// pub fn decoded_content_length(&self) -> u64 {
//     self.body_length.unwrap_or_default()
// }

#[cfg(test)]
mod tests {
    use super::AwsChunkedBody;
    use crate::content_encoding::AwsChunkedBodyOptions;
    use aws_smithy_checksums::body::ChecksumBody;
    use aws_smithy_http::body::SdkBody;
    use bytes::Buf;
    use bytes_utils::SegmentedBuf;
    use http_body::Body;
    use std::io::Read;

    #[tokio::test]
    async fn test_aws_chunked_encoded_body() {
        let input_text = "Hello world";
        let sdk_body = SdkBody::from(input_text);
        let checksum_body = ChecksumBody::new("sha256", sdk_body);
        let aws_chunked_body_options = AwsChunkedBodyOptions {
            stream_length: Some(input_text.len()),
            chunk_length: None,
            trailer_lens: vec![
                "x-amz-checksum-sha256:ZOyIygCyaOW6GjVnihtTFtIS9PNmskdyMlNKiuyjfzw=".len(),
            ],
        };
        let mut aws_chunked_body = AwsChunkedBody::new(checksum_body, aws_chunked_body_options);

        let mut output = SegmentedBuf::new();
        while let Some(buf) = aws_chunked_body.data().await {
            output.push(buf.unwrap());
        }

        let mut actual_output = String::new();
        output
            .reader()
            .read_to_string(&mut actual_output)
            .expect("Doesn't cause IO errors");

        let expected_output = "B\r\nHello world\r\n0\r\nx-amz-checksum-sha256:ZOyIygCyaOW6GjVnihtTFtIS9PNmskdyMlNKiuyjfzw=\r\n\r\n";

        // Verify data is complete and correctly encoded
        assert_eq!(expected_output, actual_output);

        assert!(
            aws_chunked_body
                .trailers()
                .await
                .expect("checksum generation was without error")
                .is_none(),
            "aws-chunked encoded bodies don't have normal HTTP trailers"
        );
    }
}
