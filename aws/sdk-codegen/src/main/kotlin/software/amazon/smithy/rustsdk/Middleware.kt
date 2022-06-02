package software.amazon.smithy.rustsdk

import software.amazon.smithy.rust.codegen.endpoints.EndpointsGenerator
import software.amazon.smithy.rust.codegen.rustlang.*
import software.amazon.smithy.rust.codegen.smithy.CodegenContext
import software.amazon.smithy.rust.codegen.smithy.RuntimeConfig
import software.amazon.smithy.rust.codegen.smithy.RuntimeType
import software.amazon.smithy.rustsdk.customize.s3.S3Decorator

class Middleware(private val runtimeConfig: RuntimeConfig, private val layers: List<Writable>) {
    private val types = Types(runtimeConfig)
    fun middleware(): RuntimeType = RuntimeType.forInlineFun("middleware", RustModule.public("middleware")) {
        val f = "impl std::future::Future<Output=Result<Response, #{SendOperationError}>> + Send + 'static"
        it.rustTemplate(
            """
            pub(crate) fn middleware<
                Connector: #{Service}<#{Request}, Error=#{SendOperationError}, Response=Response, Future=$f> + Clone + Send + Sync + 'static, 
                Response: 'static
            >() -> impl #{Layer}<Connector, Service = 
                      impl #{Service}<#{Request}, Error=#{SendOperationError}, Response=Response, Future=$f> + Send + Clone
                   > + Send + Sync + 'static
            {
                    #{ServiceBuilder}::new()
                        #{layers:W}
            }
            
            /*pub(crate) fn dyn_middleware<C>() -> impl {SmithyMiddleware}<C> {
                    #{ServiceBuilder}::new()
                        #{layers:W}
            }*/
        """,
            "SmithyConnector" to types.smithyConnector,
            "SmithyMiddleware" to types.smithyMiddleware,
            "ServiceBuilder" to types.serviceBuilder,
            "Request" to types.request,
            "SendOperationError" to types.sendOperationError,
            "Service" to CargoDependency.Tower.asType().member("Service"),
            "Layer" to CargoDependency.Tower.asType().member("Layer"),
            "layers" to layers()
        )
    }

    fun layers() = writable {
        layers.forEach { layer ->
            rust(".layer(#W)", layer)
        }
    }

    companion object {
        fun forContext(ctx: CodegenContext): Middleware {
            val layers = Layers(ctx.runtimeConfig)
            val params = ctx.endpointsGenerator().params()
            return Middleware(ctx.runtimeConfig, layers.defaultLayers + layers.endpointParams(params))
        }
    }
}

fun CodegenContext.endpointsGenerator(): EndpointsGenerator = S3Decorator.endpointsGenerator(this) ?: EndpointsGenerator.default()

class Layers(private val runtimeConfig: RuntimeConfig) {
    private val types = Types(runtimeConfig)
    private val scope = arrayOf(
        "MapRequestLayer" to types.mapRequest,
        "AsyncMapRequestLayer" to types.asyncMapRequest,
        "aws_http" to types.awsHttp,
        "SigV4SigningStage" to types.awsSigAuth.member("middleware::SigV4SigningStage"),
        "SigV4Signer" to types.awsSigAuth.member("signer::SigV4Signer"),
        "aws_endpoint" to runtimeConfig.awsEndpoint().asType()
    )

    val defaultLayers = listOf(
        credentialProvider(),
        signer(),
        userAgent(),
        recursionDetection()
    )

    fun endpointParams(paramsType: RuntimeType) = mapRequest {
        rustTemplate("#{aws_endpoint}::v2::EndpointStage::<#{params}>::new()", "params" to paramsType, *scope)
    }


    private fun mapRequest(inner: Writable) = writable {
        rustTemplate("#{MapRequestLayer}::for_mapper(#{inner:W})", "inner" to inner, *scope)
    }

    private fun asyncMapRequest(inner: Writable) = writable {
        rustTemplate("#{AsyncMapRequestLayer}::for_mapper(#{inner:W})", "inner" to inner, *scope)
    }

    private fun userAgent(): Writable = mapRequest {
        rustTemplate("#{aws_http}::user_agent::UserAgentStage::new()", *scope)
    }

    private fun credentialProvider() = asyncMapRequest {
        rustTemplate("#{aws_http}::auth::CredentialsStage::new()", *scope)
    }

    private fun signer() = mapRequest {
        rustTemplate("#{SigV4SigningStage}::new(#{SigV4Signer}::new())", *scope)
    }

    private fun recursionDetection() = mapRequest {
        rustTemplate("#{aws_http}::recursion_detection::RecursionDetectionStage::new()", *scope)
    }
}