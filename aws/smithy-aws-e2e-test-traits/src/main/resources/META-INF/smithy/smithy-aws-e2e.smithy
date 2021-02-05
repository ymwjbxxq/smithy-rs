$version: "1.0"
namespace aws.e2e

@trait(selector: "service")
list e2eTests {
    member: e2eTest
}

structure e2eTest {
    @required
    id: String,

    @required
    environment: TestEnvironment,

    @required
    operations: Operations,

    @required
    networkTraffic: NetworkTraffic,

    /// If true, this test describes behavior that will not be triggered
    /// by running these requests against real AWS services. `networkTraffic` must be used.
    @required
    synthetic: Boolean
}

list NetworkTraffic {
    member: NetworkEvent
}

list Operations {
    member: Operation
}

union Operation {
    /// A standard request-response pattern where the SDK dispatches a request and receives one response
    requestResponse: RequestResponse,


    /// A paginated request response pattern. The client should use pagination logic to attempt to read
    /// all available pages, potentially dispatching multiple requests
    paginateToEnd: RequestResponse

    /// A waiter-pattern: The client should dispatch the request using the specified waiter. The client should use
    /// its waiter machinery to poll until the required condition is met.
    // TODO: get the right type here; it isn't `RequestResponse` because we also need a waiter
    // waiter: RequestResponse
}

structure RequestResponse {
    request: Request,
    response: Response
}

structure Request {
    shape: ParameterizedShape,
    timestamp: Timestamp
}

union Response {
    /// A valid response is returned. This response may be an error, _but it is a modeled error_.
    success: ParameterizedShape,

    /// The should fail for some reason, eg. invalid JSON/XML, an invalid checksum, etc, server hangup
    failure: String
}

structure ParameterizedShape {
    @idRef(failWhenMissing: true)
    id: String,
    params: Document
}

structure TestEnvironment {
    /// Process Environment
    environment: StringMap,

    /// Map from filename in `~/.aws` to its contents
    awsDir: StringMap
}

union VerifiedHeader {
    /// For a request to match, the HTTP header must match exactly
    exactMatch: HttpHeader,

    /// For a request to match, the HTTP header must match the specified key
    keyMatch: String,


    /// For a request to match, the HTTP header must match but the value only needs to be a prefix
    prefixMatch: HttpHeader,

    /// For a request to match, the HTTP header must NOT be set
    unset: String
}

structure HttpHeader {
    key: String,
    value: String
}

list VerifiedHeaders {
    member: VerifiedHeader
}

/// Structure describing how an actual HTTP request must match against a recorded HTTP request
structure HttpRequestMatch {
    @required
    headers: VerifiedHeaders,

    body: BodyMatch,

    @required
    uri: String
}

@enum([{ value: "JSON" }, { value: "XML" }])
string BodyFormat

structure BodyMatch {
    format: BodyFormat,
    contents: String
}

structure NetworkEvent {
    /// The raw HTTP request. This may be used to replay this integration test
    /// against a service to validate that it is still accurate (the service would need to support time mocking)
    httpRequest: HttpRequest,

    /// How do we decide the request the client sent was OK?
    httpRequestMatch: HttpRequestMatch,
    httpResponse: HttpResponse
    // At some point, HttpResponse matching could be added to enable running these tests against a service
}

list HeaderList {
    member: HttpHeader
}

structure HttpRequest {
    @required
    headers: HeaderList,

    body: String,

    timestamp: Timestamp,

    @required
    method: String,

    @required
    uri: String,
}

structure HttpResponse {
    @required
    headers: HeaderList,

    body: String,

    @required
    status: Integer,

    reason: String,

    timestamp: Timestamp
}


map StringMap {
    key: String,
    value: String
}
