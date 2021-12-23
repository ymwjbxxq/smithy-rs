use super::*;
use async_stream::stream;
use axum_server::tls_rustls::RustlsConfig;
use futures_util::TryFutureExt;
use hyper::server::{accept, Server};
use std::io::Error;
use std::time::Duration;
use tokio::{
    net::TcpListener,
    signal::unix::{signal, SignalKind},
};
use tokio_rustls::TlsAcceptor;
use tracing::{error, info, warn};

/// Stuff
pub async fn reload_rustls_pem<F, T>(config: RustlsConfig, reload_interval: Duration, cert_and_key_callback: F) -> !
where
    F: Fn() -> T,
    T: Future<Output = Result<(Vec<u8>, Vec<u8>), Error>> + Send + 'static,
{
    loop {
        tokio::time::sleep(reload_interval).await;
        info!("Reloading Rustls configuration");
        // Reload rustls configuration from new files.
        match cert_and_key_callback().await {
            Ok((key, cert)) => {
                info!("Rustls configuration reloaded");
                config.reload_from_pem(cert, key).await.ok();
            }
            Err(e) => {
                error!("Unable to reload Rustls configuration: {}", e);
            }
        }
    }
}

/// Stuff
pub async fn bind_hyper_rustls_pem<F, T>(
    address: &str,
    router: Router,
    reload_interval: Duration,
    pem_cert_and_key: F,
) -> Result<
    hyper::Server<impl hyper::server::accept::Accept, crate::routing::into_make_service::IntoMakeService<Router>>,
    ServerError,
>
where
    F: Fn() -> T + Sync + Send + 'static,
    T: Future<Output = Result<(Vec<u8>, Vec<u8>), Error>> + Send + 'static,
{
    let (key, cert) = pem_cert_and_key().await?;
    let config = RustlsConfig::from_pem(cert, key).await?;

    // Spawn a task to reload tls.
    tokio::spawn(reload_rustls_pem(config.clone(), reload_interval, pem_cert_and_key));

    // Create a TCP listener via tokio.
    let tcp = TcpListener::bind(&address).await?;
    let tls_acceptor = TlsAcceptor::from(config.get_inner());
    // Prepare a long-running future stream to accept and serve clients.
    let incoming_tls_stream = stream! {
        loop {
            let (socket, _) = tcp.accept().await?;
            let stream = tls_acceptor.accept(socket).map_err(|e| {
                error!("TLS accept error: {}", e);
                ServerError::Tls(e.to_string())
            });
            yield stream.await;
        }
    };

    let acceptor = accept::from_stream(incoming_tls_stream);
    let app = router.into_make_service();
    let server = Server::builder(acceptor).serve(app);

    // Run the future, keep going until an error occurs.
    // Ok(server.with_graceful_shutdown(async {
    //     let mut signal_terminate =
    //         signal(SignalKind::terminate()).expect("Unable to register SIGTERM");
    //     let mut signal_interrupt =
    //         signal(SignalKind::interrupt()).expect("Unable to register SIGINT");

    //     tokio::select! {
    //         _ = signal_terminate.recv() => warn!("Caught SIGTERM, stopping service"),
    //         _ = signal_interrupt.recv() => warn!("Caught SIGINT, stopping service")
    //     }
    // }))
    Ok(server)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::routing::{request_spec::RequestSpec, Router};
    use crate::{body::empty, boxed, Body, BoxBody, BoxError};
    use http::{Method, Request, Response};
    use hyper::service::{make_service_fn, service_fn};
    use std::convert::Infallible;
    use tokio::io::AsyncReadExt;
    use tower::ServiceBuilder;

    async fn echo_ok<Body>(req: Request<Body>) -> Result<Response<BoxBody>, Infallible> {
        Ok(Response::new(empty()))
    }

    async fn update_key_and_cert() -> Result<(Vec<u8>, Vec<u8>), Error> {
        let mut key_buf = Vec::new();
        let mut certificate_buf = Vec::new();
        let mut key = tokio::fs::File::open("src/tests/certs/key.pem").await?;
        let mut certificate = tokio::fs::File::open("src/tests/certs/certificate.pem").await?;
        key.read_to_end(&mut key_buf).await?;
        certificate.read_to_end(&mut certificate_buf).await?;
        Ok((key_buf, certificate_buf))
    }

    #[tokio::test]
    async fn bind_hyper_rustls_ok() {
        let request_spec = crate::routing::request_spec::RequestSpec::new(
            http::Method::PUT,
            crate::routing::request_spec::UriSpec {
                host_prefix: None,
                path_and_query: crate::routing::request_spec::PathAndQuerySpec {
                    path_segments: crate::routing::request_spec::PathSpec::from_vector_unchecked(vec![
                        crate::routing::request_spec::PathSegment::Literal(String::from("some")),
                        crate::routing::request_spec::PathSegment::Label,
                    ]),
                    query_segments: crate::routing::request_spec::QuerySpec::from_vector_unchecked(vec![]),
                },
            },
        );
        let router: Router<Body> = Router::new().route(request_spec, service_fn(echo_ok));
        let server = bind_hyper_rustls_pem("0.0.0.0:13743", router, Duration::from_secs(10), update_key_and_cert);
        let server = server.await.unwrap();
        tokio::task::spawn(server);
        tokio::time::sleep(Duration::from_millis(100)).await;

        let res = reqwest::Client::builder()
            .danger_accept_invalid_certs(true)
            .build()
            .unwrap()
            .get("https://localhost:13743/some")
            .send()
            .await
            .unwrap();
        println!("{:#?}", res);
    }
}
