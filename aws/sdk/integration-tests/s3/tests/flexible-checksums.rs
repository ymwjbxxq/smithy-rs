/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

use aws_http::user_agent::AwsUserAgent;
use aws_sdk_s3::middleware::DefaultMiddleware;
use aws_sdk_s3::model::{Tag, Tagging};
use aws_sdk_s3::operation::{PutBucketTagging, PutObject};
use aws_sdk_s3::types::ByteStream;
use aws_sdk_s3::{Credentials, Region};
use aws_smithy_client::test_connection::capture_request;
use aws_smithy_client::Client as CoreClient;
use std::time::{Duration, UNIX_EPOCH};

pub type Client<C> = CoreClient<C, DefaultMiddleware>;

const BODY_STR: &str = r#"Hello world"#;

#[tokio::test]
async fn test_calculate_sha1_checksum_for_streaming_request() -> Result<(), aws_sdk_s3::Error> {
    tracing_subscriber::fmt::init();
    let config = aws_config::load_from_env().await;
    let client = aws_sdk_s3::Client::new(&config);

    let op = client
        .put_object()
        .bucket("telephone-game")
        .key("checksum_test.txt")
        .body(ByteStream::from_static(BODY_STR.as_bytes()))
        .checksum_algorithm(aws_sdk_s3::model::ChecksumAlgorithm::Sha256);

    op.send().await.unwrap();

    Ok(())
}

#[tokio::test]
async fn test_calculate_sha1_checksum_for_request() -> Result<(), aws_sdk_s3::Error> {
    let config = aws_config::load_from_env().await;
    let client = aws_sdk_s3::Client::new(&config);

    let op = client
        .put_bucket_tagging()
        .bucket("telephone-game")
        .tagging(
            Tagging::builder()
                .tag_set(
                    Tag::builder()
                        .key("test_tag_key")
                        .value("test_tag_value")
                        .build(),
                )
                .build(),
        )
        .checksum_algorithm(aws_sdk_s3::model::ChecksumAlgorithm::Sha1);

    let _ = op.send().await.unwrap();

    Ok(())
}

#[tokio::test]
async fn test_sha1_checksum_is_correctly_calculated_and_header_is_set(
) -> Result<(), aws_sdk_s3::Error> {
    let creds = Credentials::new(
        "ANOTREAL",
        "notrealrnrELgWzOk3IfjzDKtFBhDby",
        Some("notarealsessiontoken".to_string()),
        None,
        "test",
    );
    let conf = aws_sdk_s3::Config::builder()
        .credentials_provider(creds)
        .region(Region::new("us-east-1"))
        .build();

    use aws_sdk_s3::model::ChecksumAlgorithm;
    let checksums_to_test = [
        ("crc32", "x-amz-checksum-crc32", ChecksumAlgorithm::Crc32),
        ("crc32c", "x-amz-checksum-crc32c", ChecksumAlgorithm::Crc32C),
        ("sha1", "x-amz-checksum-sha1", ChecksumAlgorithm::Sha1),
        ("sha256", "x-amz-checksum-sha256", ChecksumAlgorithm::Sha256),
    ];

    for (checksum_name, checksum_name_header, checksum_algorithm) in checksums_to_test.into_iter() {
        let (conn, receiver) = capture_request(None);

        let client = Client::new(conn.clone());
        let tagging = Tagging::builder()
            .tag_set(
                Tag::builder()
                    .key("test_tag_key")
                    .value("test_tag_value")
                    .build(),
            )
            .build();
        let builder = PutBucketTagging::builder()
            .bucket("a-bucket")
            .tagging(tagging)
            .checksum_algorithm(checksum_algorithm);

        let mut op = builder
            .build()
            .unwrap()
            .make_operation(&conf)
            .await
            .unwrap();
        op.properties_mut()
            .insert(UNIX_EPOCH + Duration::from_secs(1624036048));
        op.properties_mut().insert(AwsUserAgent::for_tests());

        client.call(op).await.unwrap();

        let expected_req = receiver.expect_request();
        let actual_checksum_header = expected_req.headers().get(checksum_name_header).unwrap();

        // Calculate the expected checksum on our own so that we have something to compare against
        let mut sha1_checksum_callback = aws_smithy_checksums::str_to_body_callback(checksum_name);
        sha1_checksum_callback
            .update(expected_req.body().bytes().unwrap())
            .unwrap();
        let headers = sha1_checksum_callback.trailers().unwrap().unwrap();
        let expected_checksum_header = headers.get(checksum_name_header).unwrap();

        assert_eq!(actual_checksum_header, expected_checksum_header);
    }

    Ok(())
}

#[tokio::test]
async fn test_streaming_request_sets_x_amz_trailer_header_when_checksum_is_enabled(
) -> Result<(), aws_sdk_s3::Error> {
    let creds = Credentials::new(
        "ANOTREAL",
        "notrealrnrELgWzOk3IfjzDKtFBhDby",
        Some("notarealsessiontoken".to_string()),
        None,
        "test",
    );
    let conf = aws_sdk_s3::Config::builder()
        .credentials_provider(creds)
        .region(Region::new("us-east-1"))
        .build();
    let (conn, receiver) = capture_request(None);

    let client = Client::new(conn.clone());
    let builder = PutObject::builder()
        .bucket("telephone-game")
        .key("checksum_test.txt")
        .body(ByteStream::from_static(b"test text"))
        .checksum_algorithm(aws_sdk_s3::model::ChecksumAlgorithm::Sha256);

    let mut op = builder
        .build()
        .unwrap()
        .make_operation(&conf)
        .await
        .unwrap();
    op.properties_mut()
        .insert(UNIX_EPOCH + Duration::from_secs(1624036048));
    op.properties_mut().insert(AwsUserAgent::for_tests());

    client.call(op).await.unwrap();

    let expected_req = receiver.expect_request();
    let x_amz_trailer_header = expected_req
        .headers()
        .get("x-amz-trailer")
        .unwrap()
        .to_owned();

    assert!(
        x_amz_trailer_header
            .to_str()
            .unwrap()
            .contains("x-amz-checksum-sha256"),
        "x_amz_trailer header did not match expected: was \"{}\", expected it to be \"x-amz-checksum-sha256\"",
        x_amz_trailer_header.to_str().unwrap(),
    );

    Ok(())
}
