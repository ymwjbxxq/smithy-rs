package software.amazon.smithy.rust.codegen.endpoints

import software.amazon.smithy.aws.reterminus.Endpoint
import software.amazon.smithy.aws.reterminus.EndpointRuleset
import software.amazon.smithy.aws.reterminus.EndpointTestSuite
import software.amazon.smithy.aws.reterminus.lang.expr.Expr
import software.amazon.smithy.aws.reterminus.lang.parameters.Builtins
import software.amazon.smithy.aws.reterminus.lang.parameters.Parameters
import software.amazon.smithy.aws.reterminus.lang.rule.Rule
import software.amazon.smithy.rust.codegen.rustlang.Attribute
import software.amazon.smithy.rust.codegen.rustlang.RustMetadata
import software.amazon.smithy.rust.codegen.smithy.CodegenContext
import software.amazon.smithy.rust.codegen.smithy.RuntimeConfig
import software.amazon.smithy.rust.codegen.smithy.RuntimeType
import software.amazon.smithy.rust.codegen.smithy.RustCrate
import software.amazon.smithy.rust.codegen.util.expectTrait

class EndpointsGenerator(
    private val rules: EndpointRuleset,
    private val tests: List<EndpointTestSuite>,
    private val runtimeConfig: RuntimeConfig
) {
    private val generator = EndpointsRulesGenerator(rules, runtimeConfig)

    fun paramsType() = generator.endpointParamStruct()

    fun params() = rules.parameters

    fun resolver() = generator.endpointResolver()
    fun generate(crate: RustCrate): RuntimeType {
        val generator = EndpointsRulesGenerator(rules, runtimeConfig)
        crate.lib {
            it.withModule("tests", RustMetadata(public = false, additionalAttributes = listOf(Attribute.Cfg("test")))) {
                tests.forEach { testSuite ->
                    EndpointTestGenerator(
                        testSuite,
                        generator.endpointParamsBuilder(),
                        generator.endpointResolver(),
                        rules.parameters,
                        runtimeConfig
                    ).generate()(this)
                }
            }
        }
        return generator.endpointResolver()
    }

}