/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

//! Base Middleware Stack

pub use aws_smithy_client::retry::Config as RetryConfig;

use aws_endpoint::AwsEndpointStage;
use aws_http::auth::CredentialsStage;
use aws_http::recursion_detection::RecursionDetectionStage;
use aws_http::user_agent::UserAgentStage;
use aws_sig_auth::middleware::SigV4SigningStage;
use aws_sig_auth::signer::SigV4Signer;
use aws_smithy_client::bounds::{SmithyConnector, SmithyMiddleware};
use aws_smithy_client::erase::boxclone::BoxCloneService;
use aws_smithy_client::erase::DynMiddleware;
use aws_smithy_http::operation::{Request};
use aws_smithy_http_tower::dispatch::DispatchService;
use aws_smithy_http_tower::map_request::{AsyncMapRequestLayer, MapRequestLayer};
use aws_smithy_http_tower::SendOperationError;
use std::fmt::Debug;
use std::future::Future;
use tower::layer::util::{Identity, Stack};
use tower::{Layer, Service, ServiceBuilder};

// define the middleware stack in a non-generic location to reduce code bloat.
pub fn middleware<C>() -> DynMiddleware<C>
where
    C: SmithyConnector,
{
    DynMiddleware::new(base_middleware())
}

pub(crate) fn base_middleware<
    Response: 'static,
    F1: Future<Output=Result<Response, SendOperationError>> + Send + 'static,
    Connector: Service<
            Request,
            Error = SendOperationError,
            Response = Response,
            Future = F1,
        > + Clone
        + Send
        + Sync
        + 'static,
>() -> impl Layer<Connector, Service = impl Service<Request, Error = SendOperationError, Response=Response, Future=impl Future<Output=Result<Response, SendOperationError>>> + Send + Clone>
       + Send
       + Sync
       + 'static
{
    let credential_provider = AsyncMapRequestLayer::for_mapper(CredentialsStage::new());
    let signer = MapRequestLayer::for_mapper(SigV4SigningStage::new(SigV4Signer::new()));
    let endpoint_resolver = MapRequestLayer::for_mapper(AwsEndpointStage);
    let user_agent = MapRequestLayer::for_mapper(UserAgentStage::new());
    let recursion_detection = MapRequestLayer::for_mapper(RecursionDetectionStage::new());
    // These layers can be considered as occurring in order, that is:
    // 1. Resolve an endpoint
    // 2. Add a user agent
    // 3. Acquire credentials
    // 4. Sign with credentials
    // (5. Dispatch over the wire)
    ServiceBuilder::new()
        .layer(endpoint_resolver)
        .layer(user_agent)
        .layer(credential_provider)
        .layer(signer)
        .layer(recursion_detection)
}


mod test {
    use tower::ServiceBuilder;
    use crate::middleware::base_middleware;
    use crate::presigning::service::PresignedRequestService;

    async fn create_presigned_service() {
        let mut service = ServiceBuilder::new().layer(base_middleware()).service(PresignedRequestService::new());
        use tower::Service;
        service.call(todo!()).await;
    }

}

/*
pub fn DefaultMiddleware<C>() -> DynMiddleware<C> where C: SmithyConnector {
    middleware()
}*/
