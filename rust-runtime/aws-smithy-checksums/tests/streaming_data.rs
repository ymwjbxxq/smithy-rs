use aws_smithy_checksums::str_to_body_callback;
use aws_smithy_http::byte_stream::ByteStream;
use http::HeaderValue;
use http_body::Body;
use tokio::fs::File;

const CRC_32_NAME: &str = "x-amz-checksum-crc32";
const CRC_32_C_NAME: &str = "x-amz-checksum-crc32c";
const SHA_1_NAME: &str = "x-amz-checksum-sha1";
const SHA_256_NAME: &str = "x-amz-checksum-sha256";

async fn load_test_file() -> File {
    File::open("tests/test_data.txt")
        .await
        .expect("open test data file")
}

// let mut stream = FnStream::new(|tx| {
//     Box::pin(async move {
//         tx.send("1").await.expect("failed to send");
//         tokio::time::sleep(Duration::from_secs(1)).await;
//         tokio::time::sleep(Duration::from_secs(1)).await;
//         tx.send("2").await.expect("failed to send");
//         tokio::time::sleep(Duration::from_secs(1)).await;
//         tx.send("3").await.expect("failed to send");
//     })
// });

fn header_value_as_checksum_string(header_value: &HeaderValue) -> String {
    let decoded_checksum = base64::decode(header_value.to_str().unwrap()).unwrap();
    let decoded_checksum = decoded_checksum
        .into_iter()
        .map(|byte| format!("{:02X?}", byte))
        .collect::<String>();

    format!("0x{}", decoded_checksum)
}

async fn test_checksum_streaming(
    checksum_algorithm: &str,
    trailer_name: &str,
    expected_checksum: &str,
) {
    let test_data = load_test_file().await;
    let mut test_data_stream = ByteStream::read_from()
        .file(test_data)
        .build()
        .await
        .unwrap();
    let checksum_callback = str_to_body_callback(checksum_algorithm);
    test_data_stream.with_body_callback(checksum_callback);
    let mut body = test_data_stream.into_inner();

    // Stream the body. If you check trailers before this, they'll report that
    // nothing was checksum-ed.
    while let Some(chunk) = body.data().await {
        let _ = chunk.expect("chunk came through the stream OK");
    }

    let trailers = body
        .trailers()
        .await
        .expect("body can be read")
        .expect("response has trailers");
    let encoded_checksum = trailers.get(trailer_name).expect("trailers have checksum");
    let decoded_checksum = header_value_as_checksum_string(encoded_checksum);

    assert_eq!(decoded_checksum, expected_checksum);
}

#[tokio::test]
async fn test_crc32_checksum_streaming() {
    test_checksum_streaming("crc32", CRC_32_NAME, "0xF10DC6AF").await;
}

#[tokio::test]
async fn test_crc32c_checksum_streaming() {
    test_checksum_streaming("crc32c", CRC_32_C_NAME, "0xF10DC6AF").await;
}

#[tokio::test]
async fn test_sha1_checksum_streaming() {
    let expected_header_value = HeaderValue::from_str("tdwT+kAxUYTcpalySNk21y5lRhc=").unwrap();

    let test_data = load_test_file().await;
    let mut test_data_stream = ByteStream::read_from()
        .file(test_data)
        .build()
        .await
        .unwrap();
    let checksum_callback = str_to_body_callback("sha1");
    test_data_stream.with_body_callback(checksum_callback);
    let mut body = test_data_stream.into_inner();

    // Stream the body. If you check trailers before this, they'll report that
    // nothing was checksum-ed.
    while let Some(chunk) = body.data().await {
        let _ = chunk.expect("chunk came through the stream OK");
    }

    let trailers = body
        .trailers()
        .await
        .expect("body can be read")
        .expect("response has trailers");
    let encoded_checksum = trailers.get(SHA_1_NAME).expect("trailers have checksum");

    assert_eq!(encoded_checksum, expected_header_value);
}

#[tokio::test]
async fn test_sha256_checksum_streaming() {
    test_checksum_streaming("sha256", SHA_256_NAME, "0xF10DC6AF").await;
}
