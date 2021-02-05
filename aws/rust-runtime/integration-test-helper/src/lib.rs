use smithy_http::body::SdkBody;
use std::task::{Context, Poll};
use http::Request;
use tower::{Service, BoxError};
use std::future::Ready;
use std::sync::{Arc, Mutex};

type ConnectVec<B> = Vec<(http::Request<SdkBody>, http::Response<B>)>;
#[derive(Clone)]
pub struct TestConnection<B> {
    data: Arc<Mutex<ConnectVec<B>>>
}

impl<B> TestConnection<B> {
    pub fn new(mut data: ConnectVec<B>) -> Self {
        data.reverse();
        TestConnection {
            data: Arc::new(Mutex::new(data))
        }
    }

}


impl<B: Into<hyper::Body>> tower::Service<http::Request<SdkBody>> for TestConnection<B> {
    type Response = http::Response<hyper::Body>;
    type Error = BoxError;
    type Future = Ready<Result<Self::Response, Self::Error>>;

    fn poll_ready(&mut self, cx: &mut Context<'_>) -> Poll<Result<(), Self::Error>> {
        Poll::Ready(Ok(()))
    }

    fn call(&mut self, req: Request<SdkBody>) -> Self::Future {
        // todo: validate request
        if let Some((req, resp)) = self.data.lock().unwrap().pop() {
            std::future::ready(Ok(resp.map(|body|body.into())))
        } else {
            std::future::ready(Err("No more data".into()))
        }
    }
}

#[cfg(test)]
mod tests {
    use smithy_http::body::SdkBody;
    use tower::BoxError;
    use crate::TestConnection;

    #[test]
    fn meets_trait_bounds() {
        fn check() ->  impl tower::Service<
            http::Request<SdkBody>,
            Response = http::Response<hyper::Body>,
            Error = BoxError,
            Future = impl Send
        > + Clone {
            TestConnection::<String>::new(vec![])

        }
    }
}
