use crate::ChecksumCallback;

use aws_smithy_http::body::SdkBody;
use aws_smithy_http::header::append_merge_header_maps;

use bytes::{Buf, Bytes};
use http::{HeaderMap, HeaderValue};
use http_body::{Body, SizeHint};
use pin_project::pin_project;

use http::header::HeaderName;
use std::pin::Pin;
use std::task::{Context, Poll};

#[pin_project]
pub struct ChecksumBody<InnerBody> {
    #[pin]
    inner: InnerBody,
    #[pin]
    checksum_callback: ChecksumCallback,
}

impl ChecksumBody<SdkBody> {
    pub fn new(checksum_algorithm: &str, body: SdkBody) -> Self {
        Self {
            checksum_callback: ChecksumCallback::new(checksum_algorithm),
            inner: body,
        }
    }

    pub fn trailer_name(&self) -> HeaderName {
        self.checksum_callback.trailer_name()
    }

    pub fn trailer_length(&self) -> u64 {
        self.checksum_callback
            .size_hint()
            .exact()
            .expect("will always have know size")
    }

    fn poll_inner(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
    ) -> Poll<Option<Result<Bytes, aws_smithy_http::body::Error>>> {
        let this = self.project();
        let inner = this.inner;
        let mut checksum_callback = this.checksum_callback;

        match inner.poll_data(cx) {
            Poll::Ready(Some(Ok(mut data))) => {
                let len = data.chunk().len();
                let bytes = data.copy_to_bytes(len);

                if let Err(e) = checksum_callback.update(&bytes) {
                    return Poll::Ready(Some(Err(e)));
                }

                Poll::Ready(Some(Ok(bytes)))
            }
            Poll::Ready(None) => Poll::Ready(None),
            Poll::Ready(Some(Err(e))) => Poll::Ready(Some(Err(e))),
            Poll::Pending => Poll::Pending,
        }
    }
}

impl http_body::Body for ChecksumBody<SdkBody> {
    type Data = Bytes;
    type Error = aws_smithy_http::body::Error;

    fn poll_data(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
    ) -> Poll<Option<Result<Self::Data, Self::Error>>> {
        self.poll_inner(cx)
    }

    fn poll_trailers(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
    ) -> Poll<Result<Option<HeaderMap<HeaderValue>>, Self::Error>> {
        let this = self.project();
        match (
            this.checksum_callback.trailers(),
            http_body::Body::poll_trailers(this.inner, cx),
        ) {
            // If everything is ready, return trailers, merging them if we have more than one map
            (Ok(outer_trailers), Poll::Ready(Ok(inner_trailers))) => {
                let trailers = match (outer_trailers, inner_trailers) {
                    // Values from the inner trailer map take precedent over values from the outer map
                    (Some(outer), Some(inner)) => Some(append_merge_header_maps(inner, outer)),
                    // If only one or neither produced trailers, just combine the `Option`s with `or`
                    (outer, inner) => outer.or(inner),
                };
                Poll::Ready(Ok(trailers))
            }
            // If the inner poll is Ok but the outer body's checksum callback encountered an error,
            // return the error
            (Err(e), Poll::Ready(Ok(_))) => Poll::Ready(Err(e)),
            // Otherwise return the result of the inner poll.
            // It may be pending or it may be ready with an error.
            (_, inner_poll) => inner_poll,
        }
    }

    fn is_end_stream(&self) -> bool {
        self.inner.is_end_stream()
    }

    fn size_hint(&self) -> SizeHint {
        let body_size_hint = self.inner.size_hint();
        match body_size_hint.exact() {
            Some(size) => {
                let checksum_size_hint = self
                    .checksum_callback
                    .size_hint()
                    .exact()
                    .expect("checksum size is always known");
                SizeHint::with_exact(size + checksum_size_hint)
            }
            // TODO is this the right behavior?
            None => {
                let checksum_size_hint = self
                    .checksum_callback
                    .size_hint()
                    .exact()
                    .expect("checksum size is always known");
                let mut summed_size_hint = SizeHint::new();
                summed_size_hint.set_lower(body_size_hint.lower() + checksum_size_hint);

                if let Some(body_size_hint_upper) = body_size_hint.upper() {
                    summed_size_hint.set_upper(body_size_hint_upper + checksum_size_hint);
                }

                summed_size_hint
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::ChecksumBody;
    use crate::CRC_32_HEADER_NAME;
    use aws_smithy_http::body::SdkBody;
    use aws_smithy_types::base64;
    use bytes::Buf;
    use bytes_utils::SegmentedBuf;
    use http_body::Body;
    use std::io::Read;

    fn header_value_as_checksum_string(header_value: &http::HeaderValue) -> String {
        let decoded_checksum = base64::decode(header_value.to_str().unwrap()).unwrap();
        let decoded_checksum = decoded_checksum
            .into_iter()
            .map(|byte| format!("{:02X?}", byte))
            .collect::<String>();

        format!("0x{}", decoded_checksum)
    }

    #[tokio::test]
    async fn test_checksum_body() {
        let input_text = "This is some test text for an SdkBody";
        let body = SdkBody::from(input_text);
        let mut body = ChecksumBody::new("crc32", body);

        let mut output = SegmentedBuf::new();
        while let Some(buf) = body.data().await {
            output.push(buf.unwrap());
        }

        let mut output_text = String::new();
        output
            .reader()
            .read_to_string(&mut output_text)
            .expect("Doesn't cause IO errors");
        // Verify data is complete and unaltered
        assert_eq!(input_text, output_text);

        let trailers = body
            .trailers()
            .await
            .expect("checksum generation was without error")
            .expect("trailers were set");
        let checksum_trailer = trailers
            .get(CRC_32_HEADER_NAME)
            .expect("trailers contain crc32 checksum");
        let checksum_trailer = header_value_as_checksum_string(checksum_trailer);

        // Known correct checksum for the input "This is some test text for an SdkBody"
        assert_eq!("0x99B01F72", checksum_trailer);
    }
}
