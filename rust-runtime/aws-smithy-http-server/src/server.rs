/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0.
 */

//! Tls implementation using [`rustls`].
use hyper::Server;
use std::future::Future;
use thiserror::Error;

use crate::Router;

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

#[cfg(feature = "hyper-rustls")]
pub mod hyper_rustls {
    use super::*;
    use async_stream::stream;
    use axum_server::tls_rustls::RustlsConfig;
    use futures_util::TryFutureExt;
    use hyper::server::accept;
    use std::io::Error;
    use std::sync::{
        atomic::{AtomicU64, Ordering},
        Arc,
    };
    use std::time::Duration;
    use tokio::{
        net::TcpListener,
        signal::unix::{signal, SignalKind},
    };
    use tokio_rustls::TlsAcceptor;
    use tracing::{debug, error, info};

    /// Stuff
    pub async fn reload_rustls<F1, T1, F2, T2>(
        config: RustlsConfig,
        reload_interval: Duration,
        pem_cert_and_key: Option<F1>,
        der_cert_and_key: Option<F2>,
    ) -> !
    where
        F1: Fn() -> T1,
        T1: Future<Output = Result<(Vec<u8>, Vec<u8>), Error>> + Send + 'static,
        F2: Fn() -> T2,
        T2: Future<Output = Result<(Vec<u8>, Vec<Vec<u8>>), Error>> + Send + 'static,
    {
        loop {
            tokio::time::sleep(reload_interval).await;
            info!("Reloading Rustls configuration");
            // Reload rustls configuration from new files.
            if let Some(ref callback) = pem_cert_and_key {
                if let Ok((key, cert)) = callback().await {
                    config.reload_from_pem(cert, key).await.ok();
                }
            } else if let Some(ref callback) = der_cert_and_key {
                if let Ok((key, cert)) = callback().await {
                    config.reload_from_der(cert, key).await.ok();
                }
            } else {
                error!("Missing CA reload callback");
            };
            info!("Rustls configuration reloaded");
        }
    }

    /// Stuff
    pub async fn new_rustls_config<F1, T1, F2, T2>(
        pem_cert_and_key: Option<&F1>,
        der_cert_and_key: Option<&F2>,
    ) -> Result<RustlsConfig, ServerError>
    where
        F1: Fn() -> T1 + Sync + Send + 'static,
        T1: Future<Output = Result<(Vec<u8>, Vec<u8>), Error>> + Send + 'static,
        F2: Fn() -> T2 + Sync + Send + 'static,
        T2: Future<Output = Result<(Vec<u8>, Vec<Vec<u8>>), Error>> + Send + 'static,
    {
        if pem_cert_and_key.is_some() && der_cert_and_key.is_some() {
            return Err(ServerError::RefreshCallback);
        }

        if let Some(ref callback) = pem_cert_and_key {
            let (key, cert) = callback().await?;
            Ok(RustlsConfig::from_pem(cert, key).await?)
        } else if let Some(ref callback) = der_cert_and_key {
            let (key, cert) = callback().await?;
            Ok(RustlsConfig::from_der(cert, key).await?)
        } else {
            Err(ServerError::RefreshCallback)
        }
    }

    /// Stuff
    pub async fn bind_hyper_rustls<F1, T1, F2, T2>(
        address: &str,
        router: Router,
        reload_interval: Duration,
        pem_cert_and_key: Option<F1>,
        der_cert_and_key: Option<F2>,
    ) -> Result<impl Future, ServerError>
    where
        F1: Fn() -> T1 + Sync + Send + 'static,
        T1: Future<Output = Result<(Vec<u8>, Vec<u8>), Error>> + Send + 'static,
        F2: Fn() -> T2 + Sync + Send + 'static,
        T2: Future<Output = Result<(Vec<u8>, Vec<Vec<u8>>), Error>> + Send + 'static,
    {
        let config = new_rustls_config(pem_cert_and_key.as_ref(), der_cert_and_key.as_ref()).await?;

        // Spawn a task to reload tls.
        tokio::spawn(reload_rustls(
            config.clone(),
            reload_interval,
            pem_cert_and_key,
            der_cert_and_key,
        ));

        let inflight_requests = Arc::new(AtomicU64::new(0));
        // Create a TCP listener via tokio.
        let tcp = TcpListener::bind(&address).await?;
        let tls_acceptor = TlsAcceptor::from(config.get_inner());
        // Prepare a long-running future stream to accept and serve clients.
        let inflight_requests_inner = inflight_requests.clone();
        let incoming_tls_stream = stream! {
            loop {
                let (socket, _) = tcp.accept().await?;
                inflight_requests_inner.fetch_add(1, Ordering::SeqCst);
                let stream = tls_acceptor.accept(socket).map_err(|e| {
                    error!("TLS accept error: {}", e);
                    ServerError::Tls(e.to_string())
                });
                yield stream.await;
                inflight_requests_inner.fetch_sub(1, Ordering::SeqCst);
            }
        };

        let acceptor = accept::from_stream(incoming_tls_stream);
        let app = router.into_make_service();
        let server = Server::builder(acceptor).serve(app);

        // Run the future, keep going until an error occurs.
        Ok(server.with_graceful_shutdown(gracefull_shutdown(inflight_requests)))
    }

    fn drain_wait(inflight_requests: Arc<AtomicU64>) {
        let mut count = 1;
        while count > 0 {
            count = inflight_requests.load(Ordering::SeqCst);
            debug!("Remaining {} inflight requests to drain", count);
        }
        info!("Server shutdown complete");
    }

    async fn gracefull_shutdown(inflight_requests: Arc<AtomicU64>) {
        let mut signal_terminate = signal(SignalKind::terminate()).unwrap();
        let mut signal_interrupt = signal(SignalKind::interrupt()).unwrap();

        tokio::select! {
            _ = signal_terminate.recv() => drain_wait(inflight_requests),
            _ = signal_interrupt.recv() => drain_wait(inflight_requests)
        }
    }
}
