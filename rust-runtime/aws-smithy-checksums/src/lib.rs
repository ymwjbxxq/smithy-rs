/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

//! Checksum calculation and verification callbacks

use aws_smithy_types::base64;

use http::header::{HeaderMap, HeaderName, HeaderValue};
use http_body::SizeHint;
use sha1::Digest;
use std::io::Write;

pub mod body;

pub const CRC_32_NAME: &str = "crc32";
pub const CRC_32_C_NAME: &str = "crc32c";
pub const SHA_1_NAME: &str = "sha1";
pub const SHA_256_NAME: &str = "sha256";

pub const CRC_32_HEADER_NAME: &str = "x-amz-checksum-crc32";
pub const CRC_32_C_HEADER_NAME: &str = "x-amz-checksum-crc32c";
pub const SHA_1_HEADER_NAME: &str = "x-amz-checksum-sha1";
pub const SHA_256_HEADER_NAME: &str = "x-amz-checksum-sha256";

const WITH_OPTIONAL_WHITESPACE: bool = true;
const TRAILER_SEPARATOR: &str = if WITH_OPTIONAL_WHITESPACE { ": " } else { ":" };

type BoxError = Box<dyn std::error::Error + Send + Sync>;

#[derive(Debug, Default)]
struct Crc32Callback {
    hasher: crc32fast::Hasher,
}

impl Crc32Callback {
    fn update(&mut self, bytes: &[u8]) -> Result<(), BoxError> {
        self.hasher.update(bytes);

        Ok(())
    }

    fn trailers(&self) -> Result<Option<HeaderMap<HeaderValue>>, BoxError> {
        let mut header_map = HeaderMap::new();
        header_map.insert(Self::header_name(), self.header_value());

        Ok(Some(header_map))
    }

    // Size of the checksum in bytes
    fn size() -> usize {
        4
    }

    fn header_name() -> HeaderName {
        HeaderName::from_static(CRC_32_HEADER_NAME)
    }

    fn header_value(&self) -> HeaderValue {
        // We clone the hasher because `Hasher::finalize` consumes `self`
        let hash = self.hasher.clone().finalize();
        HeaderValue::from_str(&base64::encode(u32::to_be_bytes(hash)))
            .expect("base64 will always produce valid header values from checksums")
    }
}

#[derive(Debug, Default)]
struct Crc32cCallback {
    state: Option<u32>,
}

impl Crc32cCallback {
    fn update(&mut self, bytes: &[u8]) -> Result<(), BoxError> {
        self.state = match self.state {
            Some(crc) => Some(crc32c::crc32c_append(crc, bytes)),
            None => Some(crc32c::crc32c(bytes)),
        };

        Ok(())
    }

    fn trailers(&self) -> Result<Option<HeaderMap<HeaderValue>>, BoxError> {
        let mut header_map = HeaderMap::new();
        header_map.insert(Self::header_name(), self.header_value());

        Ok(Some(header_map))
    }

    // Size of the checksum in bytes
    fn size() -> usize {
        4
    }

    fn header_name() -> HeaderName {
        HeaderName::from_static(CRC_32_C_HEADER_NAME)
    }

    fn header_value(&self) -> HeaderValue {
        // If no data was provided to this callback and no CRC was ever calculated, return zero as the checksum.
        let hash = self.state.unwrap_or_default();
        HeaderValue::from_str(&base64::encode(u32::to_be_bytes(hash)))
            .expect("base64 will always produce valid header values from checksums")
    }
}

#[derive(Debug, Default)]
struct Sha1Callback {
    hasher: sha1::Sha1,
}

impl Sha1Callback {
    fn update(&mut self, bytes: &[u8]) -> Result<(), BoxError> {
        self.hasher.write_all(bytes)?;

        Ok(())
    }

    fn trailers(&self) -> Result<Option<HeaderMap<HeaderValue>>, BoxError> {
        let mut header_map = HeaderMap::new();
        header_map.insert(Self::header_name(), self.header_value());

        Ok(Some(header_map))
    }

    // Size of the checksum in bytes
    fn size() -> usize {
        20
    }

    fn header_name() -> HeaderName {
        HeaderName::from_static(SHA_1_HEADER_NAME)
    }

    fn header_value(&self) -> HeaderValue {
        // We clone the hasher because `Hasher::finalize` consumes `self`
        let hash = self.hasher.clone().finalize();
        HeaderValue::from_str(&base64::encode(&hash[..]))
            .expect("base64 will always produce valid header values from checksums")
    }
}

#[derive(Debug, Default)]
struct Sha256Callback {
    hasher: sha2::Sha256,
}

impl Sha256Callback {
    fn update(&mut self, bytes: &[u8]) -> Result<(), BoxError> {
        self.hasher.write_all(bytes)?;

        Ok(())
    }

    fn trailers(&self) -> Result<Option<HeaderMap<HeaderValue>>, BoxError> {
        let mut header_map = HeaderMap::new();
        header_map.insert(Self::header_name(), self.header_value());

        Ok(Some(header_map))
    }

    // Size of the checksum in bytes
    fn size() -> usize {
        32
    }

    fn header_name() -> HeaderName {
        HeaderName::from_static(SHA_256_HEADER_NAME)
    }

    fn header_value(&self) -> HeaderValue {
        // We clone the hasher because `Hasher::finalize` consumes `self`
        let hash = self.hasher.clone().finalize();
        HeaderValue::from_str(&base64::encode(&hash[..]))
            .expect("base64 will always produce valid header values from checksums")
    }
}

enum Inner {
    Crc32(Crc32Callback),
    Crc32c(Crc32cCallback),
    Sha1(Sha1Callback),
    Sha256(Sha256Callback),
}

pub struct ChecksumCallback(Inner);

impl ChecksumCallback {
    pub fn new(checksum_algorithm: &str) -> Self {
        if checksum_algorithm.eq_ignore_ascii_case(CRC_32_NAME) {
            Self(Inner::Crc32(Crc32Callback::default()))
        } else if checksum_algorithm.eq_ignore_ascii_case(CRC_32_C_NAME) {
            Self(Inner::Crc32c(Crc32cCallback::default()))
        } else if checksum_algorithm.eq_ignore_ascii_case(SHA_1_NAME) {
            Self(Inner::Sha1(Sha1Callback::default()))
        } else if checksum_algorithm.eq_ignore_ascii_case(SHA_256_NAME) {
            Self(Inner::Sha256(Sha256Callback::default()))
        } else {
            panic!("unsupported checksum algorithm '{}'", checksum_algorithm)
        }
    }

    pub fn update(&mut self, bytes: &[u8]) -> Result<(), BoxError> {
        match &mut self.0 {
            Inner::Crc32(ref mut callback) => callback.update(bytes)?,
            Inner::Crc32c(ref mut callback) => callback.update(bytes)?,
            Inner::Sha1(ref mut callback) => callback.update(bytes)?,
            Inner::Sha256(ref mut callback) => callback.update(bytes)?,
        };

        Ok(())
    }

    pub fn trailers(&self) -> Result<Option<HeaderMap<HeaderValue>>, BoxError> {
        match &self.0 {
            Inner::Sha256(callback) => callback.trailers(),
            Inner::Crc32c(callback) => callback.trailers(),
            Inner::Crc32(callback) => callback.trailers(),
            Inner::Sha1(callback) => callback.trailers(),
        }
    }

    pub fn trailer_name(&self) -> HeaderName {
        match &self.0 {
            Inner::Sha256(_) => Sha256Callback::header_name(),
            Inner::Crc32c(_) => Crc32cCallback::header_name(),
            Inner::Crc32(_) => Crc32Callback::header_name(),
            Inner::Sha1(_) => Sha1Callback::header_name(),
        }
    }

    // TODO I don't think we should call it this or return a `SizeHint`.
    //      Instead, just return the size as u64
    pub fn size_hint(&self) -> SizeHint {
        let (trailer_name_size_in_bytes, checksum_size_in_bytes) = match &self.0 {
            // We want to get the size of the actual `HeaderName` except those don't have a `.len()`
            // method so we'd have to convert back to the original string. That's why we're getting
            // `.len()` from the original strings here.
            Inner::Crc32(_) => (CRC_32_HEADER_NAME.len(), Crc32Callback::size()),
            Inner::Crc32c(_) => (CRC_32_C_HEADER_NAME.len(), Crc32cCallback::size()),
            Inner::Sha1(_) => (SHA_1_HEADER_NAME.len(), Sha1Callback::size()),
            Inner::Sha256(_) => (SHA_256_HEADER_NAME.len(), Sha256Callback::size()),
        };
        // The checksums will be base64 encoded so we need to get that length
        let base64_encoded_checksum_size_in_bytes = base64::encoded_length(checksum_size_in_bytes);

        let total_size = trailer_name_size_in_bytes
            + TRAILER_SEPARATOR.len()
            + base64_encoded_checksum_size_in_bytes;

        SizeHint::with_exact(total_size as u64)
    }
}

#[cfg(test)]
mod tests {
    use super::{
        Crc32Callback, Crc32cCallback, Sha1Callback, Sha256Callback, CRC_32_C_HEADER_NAME,
        CRC_32_HEADER_NAME, SHA_1_HEADER_NAME, SHA_256_HEADER_NAME,
    };

    use aws_smithy_types::base64;
    use http::HeaderValue;
    use pretty_assertions::assert_eq;

    const TEST_DATA: &str = r#"test data"#;

    fn header_value_as_checksum_string(header_value: &HeaderValue) -> String {
        let decoded_checksum = base64::decode(header_value.to_str().unwrap()).unwrap();
        let decoded_checksum = decoded_checksum
            .into_iter()
            .map(|byte| format!("{:02X?}", byte))
            .collect::<String>();

        format!("0x{}", decoded_checksum)
    }

    #[test]
    fn test_crc32_checksum() {
        let mut checksum_callback = Crc32Callback::default();
        checksum_callback.update(TEST_DATA.as_bytes()).unwrap();
        let checksum_callback_result = checksum_callback.trailers().unwrap().unwrap();
        let encoded_checksum = checksum_callback_result.get(CRC_32_HEADER_NAME).unwrap();
        let decoded_checksum = header_value_as_checksum_string(encoded_checksum);

        let expected_checksum = "0xD308AEB2";

        assert_eq!(decoded_checksum, expected_checksum);
    }

    #[test]
    fn test_crc32c_checksum() {
        let mut checksum_callback = Crc32cCallback::default();
        checksum_callback.update(TEST_DATA.as_bytes()).unwrap();
        let checksum_callback_result = checksum_callback.trailers().unwrap().unwrap();
        let encoded_checksum = checksum_callback_result.get(CRC_32_C_HEADER_NAME).unwrap();
        let decoded_checksum = header_value_as_checksum_string(encoded_checksum);

        let expected_checksum = "0x3379B4CA";

        assert_eq!(decoded_checksum, expected_checksum);
    }

    #[test]
    fn test_sha1_checksum() {
        let mut checksum_callback = Sha1Callback::default();
        checksum_callback.update(TEST_DATA.as_bytes()).unwrap();
        let checksum_callback_result = checksum_callback.trailers().unwrap().unwrap();
        let encoded_checksum = checksum_callback_result.get(SHA_1_HEADER_NAME).unwrap();
        let decoded_checksum = header_value_as_checksum_string(encoded_checksum);

        let expected_checksum = "0xF48DD853820860816C75D54D0F584DC863327A7C";

        assert_eq!(decoded_checksum, expected_checksum);
    }

    #[test]
    fn test_sha256_checksum() {
        let mut checksum_callback = Sha256Callback::default();
        checksum_callback.update(TEST_DATA.as_bytes()).unwrap();
        let checksum_callback_result = checksum_callback.trailers().unwrap().unwrap();
        let encoded_checksum = checksum_callback_result.get(SHA_256_HEADER_NAME).unwrap();
        let decoded_checksum = header_value_as_checksum_string(encoded_checksum);

        let expected_checksum =
            "0x916F0027A575074CE72A331777C3478D6513F786A591BD892DA1A577BF2335F9";

        assert_eq!(decoded_checksum, expected_checksum);
    }
}
