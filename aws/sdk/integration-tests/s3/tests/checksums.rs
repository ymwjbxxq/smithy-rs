use aws_sdk_s3::model::ChecksumAlgorithm;
use aws_sdk_s3::operation::PutObject;
use http::HeaderValue;

#[tracing_test::traced_test]
#[tokio::test]
async fn test_checksum_on_streaming_request() {
    let sdk_config = aws_config::load_from_env().await;

    let op = PutObject::builder()
        .bucket("telephone-game")
        .key("test.txt")
        .body(aws_sdk_s3::types::ByteStream::from_static(b"Hello world"))
        .checksum_algorithm(ChecksumAlgorithm::Sha256)
        .build()
        .unwrap()
        .make_operation(&aws_sdk_s3::Config::from(&sdk_config))
        .await
        .expect("failed to construct operation");

    let (req, _) = op.into_request_response();

    let headers = req.http().headers();
    let x_amz_content_sha256 = headers
        .get("x-amz-content-sha256")
        .expect("x-amz-content-sha256 header exists");
    let x_amz_trailer = headers
        .get("x-amz-trailer")
        .expect("x-amz-trailer header exists");
    let x_amz_decoded_content_length = headers
        .get("x-amz-decoded-content-length")
        .expect("x-amz-decoded-content-length header exists");
    let content_length = headers
        .get("Content-Length")
        .expect("Content-Length header exists");

    assert_eq!(
        HeaderValue::from_static("STREAMING-UNSIGNED-PAYLOAD-TRAILER"),
        x_amz_content_sha256
    );
    assert_eq!(
        HeaderValue::from_static("x-amz-checksum-sha256"),
        x_amz_trailer
    );
    assert_eq!(HeaderValue::from_static("11"), x_amz_decoded_content_length);
    assert_eq!(HeaderValue::from_static("89"), content_length);

    tracing::debug!("Request:\n{:#?}", req);

    panic!();
}
