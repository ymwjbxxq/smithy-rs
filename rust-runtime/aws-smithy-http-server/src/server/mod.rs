/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0.
 */

//! Tls implementation using [`rustls`].
use hyper::Server;
use std::future::Future;
use thiserror::Error;

use crate::Router;

#[cfg(feature = "hyper-rustls")]
mod tls;
#[cfg(feature = "hyper-rustls")]
#[doc(inline)]
pub use tls::{bind_hyper_rustls_pem, reload_rustls_pem};

/// Stuff
#[derive(Debug, Error)]
pub enum ServerError {
    /// Only one callback for a single CA format is supported at any time.
    #[error("Both PEM and DER CA callbacks provided")]
    RefreshCallback,
    /// I/O error starting server..
    #[error("I/O error starting HTTP(s) server")]
    Io(#[from] std::io::Error),
    /// Address parsing errors.
    #[error("Unable to parse address string")]
    Parse(#[from] std::net::AddrParseError),
    /// TLS errors.
    #[error("TLS error: {0}")]
    Tls(String),
}

/// Stuff
pub async fn bind_hyper(address: &str, router: Router) -> Result<impl Future, ServerError> {
    Ok(Server::bind(&address.parse()?).serve(router.into_make_service()))
}
