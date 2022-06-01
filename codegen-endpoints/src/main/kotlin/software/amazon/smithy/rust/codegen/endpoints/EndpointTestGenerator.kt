package software.amazon.smithy.rust.codegen.endpoints

import software.amazon.smithy.aws.reterminus.EndpointTest
import software.amazon.smithy.aws.reterminus.EndpointTestSuite
import software.amazon.smithy.aws.reterminus.eval.Value
import software.amazon.smithy.aws.reterminus.lang.parameters.Parameters
import software.amazon.smithy.rust.codegen.rustlang.*
import software.amazon.smithy.rust.codegen.smithy.RuntimeConfig
import software.amazon.smithy.rust.codegen.smithy.RuntimeType
import software.amazon.smithy.rust.codegen.util.dq

class EndpointTestGenerator(
    private val endpointTest: EndpointTestSuite,
    private val builder: RuntimeType,
    private val resolver: RuntimeType,
    private val params: Parameters,
    runtimeConfig: RuntimeConfig
) {
    private val scope = arrayOf(
        "Endpoint" to CargoDependency.SmithyHttp(runtimeConfig).asType().member("endpoint::Endpoint"),
    )

    fun generate(): Writable = writable {
        var id = 0
        endpointTest.testCases.forEach { testCase ->
            id += 1

            testCase.documentation?.also { docs(it) }
            rustTemplate(
                """
                /// From: ${testCase.sourceLocation.filename}:${testCase.sourceLocation.line}    
                ##[test]
                fn test_$id() {
                    let params = #{params:W};
                    let endpoint = #{resolver}(&params);
                    #{assertion:W}
                }
            """,
                "params" to params(testCase),
                "resolver" to resolver,
                "assertion" to writable {
                    when (val ex = testCase.expectation) {
                        is EndpointTest.Expectation.Endpoint -> {
                            rustTemplate(
                                """
                                let endpoint = endpoint.expect("Expected URI: ${ex.endpoint.url}");
                                assert_eq!(endpoint, #{Endpoint}::mutable(${ex.endpoint.url.dq()}.parse().unwrap()));
                            """, *scope
                            )
                        }
                        is EndpointTest.Expectation.Error -> {
                            rust("""
                                let error = endpoint.expect_err("expected error ${ex.message}");
                                assert_eq!(error, ${ex.message.dq()});
                            """)
                        }
                    }
                }
            )
        }
    }

    private fun params(testCase: EndpointTest) = writable {
        rust("#T::default()", builder)
        testCase.params.forEach { param ->
            val id = param.left
            val value = param.right
            if(params.get(id).isPresent) {
                rust(".${id.rustName()}(${generateValue(value)})")
            }
        }
        rust(".build()")
    }

    private fun generateValue(value: Value): String {
        return when (value) {
            is Value.Str -> value.value().dq()
            is Value.Bool -> value.toString()
            else -> error("unexpected value")
        }
    }
}