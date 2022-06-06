package software.amazon.smithy.rust.codegen.endpoints

import software.amazon.smithy.aws.reterminus.Endpoint
import software.amazon.smithy.aws.reterminus.lang.rule.*
import software.amazon.smithy.aws.reterminus.visit.RuleVisitor
import software.amazon.smithy.rust.codegen.rustlang.*
import software.amazon.smithy.rust.codegen.smithy.RuntimeType

class RulesVisitor(val params: RuntimeType) : RuleVisitor<Writable> {
    var idx = 0
    private fun name(prefix: String): String = "prefix_${idx++}"
    private val module = RustModule.private("endpoint_impl", "Implementation of default endpoint provider")
    private val scope = arrayOf("params" to params)

    private fun ruleFn(prefix: String, rule: Rule, body: Writable): RuntimeType {
        val fnName = name(prefix)
        return RuntimeType.forInlineFun(name(prefix), module) {
            it.rustTemplate(
                """
                /// ${it.escape(rule.documentation.orElse(""))}
                /// ${it.escape(rule.sourceLocation.filename)}:${rule.sourceLocation.line}
                fn $fnName(params: &#{params}) -> Option<Result<#{Endpoint}, #{EndpointError}>> {
                    #{conditions:W}
                    #{ruleBody:W}
                }
                """, "conditions" to handleConditions(rule.conditions), "ruleBody" to body, *scope
            )
        }
    }

    private fun handleConditions(conditions: List<Condition>): Writable {
        val self = this
        return writable {
            conditions.forEach { it.accept(self)(this) }
        }
    }

    override fun visitTreeRule(rule: TreeRule): Writable {
        val self = this
        val ruleBody = writable {
            rule.rules.forEach { rule ->
                rustTemplate(
                    """
                    if let Some(result) = #{subrule}(params) {
                        return Some(result)
                    }
                    """, "subrule" to rule.accept(self)
                )
            }
            rustTemplate("return Some(#{EndpointError}::no_rules_matched(params))", *scope)
        }
        return writable { rust("#W", ruleFn("tree_rule", rule, ruleBody)) }
    }


    override fun visitErrorRule(rule: ErrorRule): Writable {
        TODO("Not yet implemented")
    }

    override fun visitEndpointRule(rule: EndpointRule): Writable {
        TODO("Not yet implemented")
    }

    override fun visitCondition(condition: Condition): Writable {
        TODO("Not yet implemented")
    }

    override fun visitEndpoint(endpoint: Endpoint): Writable {
        TODO("Not yet implemented")
    }
}