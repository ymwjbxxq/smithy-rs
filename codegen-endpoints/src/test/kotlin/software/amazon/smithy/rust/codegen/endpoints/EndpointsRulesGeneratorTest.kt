package software.amazon.smithy.rust.codegen.endpoints

import org.junit.jupiter.api.Test
import software.amazon.smithy.aws.reterminus.EndpointRuleset
import software.amazon.smithy.aws.reterminus.eval.Scope
import software.amazon.smithy.model.node.Node
import software.amazon.smithy.rust.codegen.rustlang.rustTemplate
import software.amazon.smithy.rust.codegen.testutil.TestRuntimeConfig
import software.amazon.smithy.rust.codegen.testutil.TestWorkspace
import software.amazon.smithy.rust.codegen.testutil.compileAndTest

internal class EndpointsRulesGeneratorTest {
    val ruleset = """
    {
      "serviceId": "minimal",
      "parameters": {
        "Region": {
          "type": "string",
          "builtIn": "AWS::Region",
          "required": true
        },
        "Bucket": {
            "type": "string",
            "required": false
        },
        "DisableHttp": {
            "type": "Boolean"
        }
      },
      "rules": [
        {
          "documentation": "base rule",
          "conditions": [
            { "fn": "isSet", "argv": [ {"ref": "DisableHttp" } ] },
            { "fn": "booleanEquals", "argv": [{"ref": "DisableHttp"}, true] }
          ],
          "endpoint": {
            "url": "{Region}.amazonaws.com",
            "authSchemes": [
              "v4"
            ],
            "authParams": {
              "v4": {
                "signingName": "serviceName",
                "signingScope": "{Region}"
              }
            }
          }
        }
      ]
    }
    """.toRuleset()
    init {
        ruleset.typecheck(Scope())
    }

    @Test
    fun `generate params`() {
        val crate = TestWorkspace.testProject()
        val generator = EndpointsRulesGenerator(ruleset, TestRuntimeConfig)
        crate.lib {
            it.rustTemplate(
                """
            fn main() {
                let params = #{Params} {
                    region: "foo".to_string(),
                    bucket: None,
                    disable_http: Some(false)
                };
            }
        """, "Params" to generator.endpointParamStruct()
            )
        }
        crate.compileAndTest()
    }

    @Test
    fun `generate rules`() {
        val crate = TestWorkspace.testProject()
        val generator = EndpointsRulesGenerator(ruleset, TestRuntimeConfig)
        crate.lib {
            it.rustTemplate(
                """
            fn main() {
                let params = #{Params} {
                    region: "foo".to_string(),
                    bucket: None,
                    disable_http: Some(false)
                };
                let endpoint = #{resolve_endpoint}(&params);
            }
        """, "Params" to generator.endpointParamStruct(), "resolve_endpoint" to generator.endpointResolver()
            )
        }
        crate.compileAndTest()

    }
}

fun String.toRuleset(): EndpointRuleset {
    return EndpointRuleset.fromNode(Node.parse(this))
}