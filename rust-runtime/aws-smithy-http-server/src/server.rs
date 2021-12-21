//! Tls implementation using [`rustls`].
//!
//! # Example
//!
//! ```rust,no_run
//! use axum::{routing::get, Router};
//! use axum_server::tls_rustls::RustlsConfig;
//! use std::net::SocketAddr;
//!
//! #[tokio::main]
//! async fn main() {
//!     let app = Router::new().route("/", get(|| async { "Hello, world!" }));
//!
//!     let config = RustlsConfig::from_pem_file(
//!         "examples/self-signed-certs/cert.pem",
//!         "examples/self-signed-certs/key.pem",
//!     )
//!     .await
//!     .unwrap();
//!
//!     let addr = SocketAddr::from(([127, 0, 0, 1], 3000));
//!     println!("listening on {}", addr);
//!     axum_server::bind_rustls(addr, config)
//!         .serve(app.into_make_service())
//!         .await
//!         .unwrap();
//! }
//! ```

use axum_server::tls_rustls::RustlsConfig;
use futures_util::future::poll_fn;
use hyper::server::{
    accept::Accept,
    conn::{AddrIncoming, Http},
};
use rustls_pemfile::{certs, pkcs8_private_keys};
use std::{
    fs::File,
    io::{BufReader, Error, ErrorKind},
    net::SocketAddr,
    pin::Pin,
    str::FromStr,
    sync::Arc,
};
use tokio::net::TcpListener;
use tokio_rustls::{
    rustls::{Certificate, PrivateKey, ServerConfig},
    TlsAcceptor,
};
use tower::MakeService;

use crate::BoxError;
use crate::Router;
use arc_swap::ArcSwap;
use std::future::Future;
use std::{fmt, io, path::Path};
use thiserror::Error;
use tokio::{
    io::{AsyncRead, AsyncWrite},
    task::spawn_blocking,
};
use tokio_rustls::server::TlsStream;

#[derive(Debug, Error)]
pub enum ServerError {
    #[error("Both PEM and DER CA callbacks provided")]
    CaCallbacks,
}

async fn hyper_server_bind_rustls<F1, T1, F2, T2>(
    bind: &str,
    router: Router,
    pem_cert_and_key: Option<F1>,
    der_cert_and_key: Option<F2>,
) -> Result<(), BoxError>
where
    F1: Fn() -> T1,
    T1: Future<Output = Result<(Vec<u8>, Vec<u8>), BoxError>> + Send + 'static,
    F2: Fn() -> T2,
    T2: Future<Output = Result<(Vec<u8>, Vec<Vec<u8>>), BoxError>> + Send + 'static,
{
    if pem_cert_and_key.is_some() && der_cert_and_key.is_some() {
        return Err(Box::new(ServerError::CaCallbacks));
    }

    let config = if let Some(callback) = pem_cert_and_key {
        let (key, cert) = callback().await?;
        RustlsConfig::from_pem(cert, key).await?
    } else if let Some(callback) = der_cert_and_key {
        let (key, cert) = callback().await?;
        RustlsConfig::from_der(cert, key).await?
    } else {
        return Err(Box::new(ServerError::CaCallbacks));
    };

    let addr = SocketAddr::from_str(bind);
    Ok(axum_server::bind_rustls(addr, config))
}
