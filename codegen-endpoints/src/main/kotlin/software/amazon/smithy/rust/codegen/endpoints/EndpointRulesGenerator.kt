package software.amazon.smithy.rust.codegen.endpoints

import org.jetbrains.annotations.Contract
import software.amazon.smithy.aws.reterminus.Endpoint
import software.amazon.smithy.aws.reterminus.EndpointRuleset
import software.amazon.smithy.aws.reterminus.eval.Type
import software.amazon.smithy.aws.reterminus.lang.Identifier
import software.amazon.smithy.aws.reterminus.lang.expr.Expr
import software.amazon.smithy.aws.reterminus.lang.expr.Literal
import software.amazon.smithy.aws.reterminus.lang.expr.Ref
import software.amazon.smithy.aws.reterminus.lang.expr.Template
import software.amazon.smithy.aws.reterminus.lang.fn.*
import software.amazon.smithy.aws.reterminus.lang.parameters.Parameter
import software.amazon.smithy.aws.reterminus.lang.parameters.ParameterType
import software.amazon.smithy.aws.reterminus.lang.rule.Condition
import software.amazon.smithy.aws.reterminus.lang.rule.EndpointRule
import software.amazon.smithy.aws.reterminus.lang.rule.ErrorRule
import software.amazon.smithy.aws.reterminus.lang.rule.Rule
import software.amazon.smithy.aws.reterminus.lang.rule.TreeRule
import software.amazon.smithy.codegen.core.Symbol
import software.amazon.smithy.rust.codegen.rustlang.*
import software.amazon.smithy.rust.codegen.smithy.*
import software.amazon.smithy.rust.codegen.util.dq
import software.amazon.smithy.rust.codegen.util.orNull
import software.amazon.smithy.rust.codegen.util.toSnakeCase

class EndpointsRulesGenerator(private val endpointsModel: EndpointRuleset, runtimeConfig: RuntimeConfig) {
    private var nameIdx = 0
    private fun nextName(): String {
        nameIdx += 1
        return "var_$nameIdx"
    }

    private val scope = arrayOf(
        "Endpoint" to CargoDependency.SmithyHttp(runtimeConfig).asType().member("endpoint::Endpoint"),
        "endpoint_util" to RuntimeType.forInlineDependency(InlineAwsDependency.forRustFile("endpoint"))
    )

    sealed class Ownership {
        object Borrowed: Ownership()
        object Owned: Ownership()
    }

    data class Context(val valueNumbering: Map<Expr, String>) {
        fun withValue(expr: Expr, name: String): Context {
            return this.copy(valueNumbering = valueNumbering.plus(expr to name))
        }

        companion object {
            fun empty() = Context(HashMap())
        }
    }

    private val endpointsModule = RustModule.public("endpoint")
    fun endpointParamStruct(): RuntimeType = RuntimeType.forInlineFun("Params", endpointsModule) {
        it.rustTemplate(
            """
            ##[non_exhaustive]
            ##[derive(Debug, Eq, PartialEq, Clone)]
            pub struct Params {
                #{params:W}
            }
        """, "params" to params()
        )
    }

    fun endpointParamsBuilder(): RuntimeType = RuntimeType.forInlineFun("Builder", endpointsModule) {
        val builderParams = writable {
            endpointsModel.parameters.toList().forEach { parameter ->
                rust("pub ${parameter.memberName()}: #T,", parameter.symbol().makeOptional())
            }
        }

        val implBlock = writable {
            endpointsModel.parameters.toList().forEach { parameter ->
                rust(
                    """
                    pub fn ${parameter.memberName()}(mut self, param: impl Into<#T>) -> Self {
                        self.${parameter.memberName()} = Some(param.into());
                        self
                    }
                """, parameter.symbol().mapRustType { it.stripOuter<RustType.Option>() }
                )
            }

            rustTemplate("""
                pub fn build(self) -> #{Params} {
                    #{Params} {
                        #{members:W}
                    }
                }
            """, "Params" to endpointParamStruct(), "members" to writable {
                endpointsModel.parameters.toList().forEach { parameter ->
                    val unwrap = if (parameter.isRequired) {
                        ".unwrap()"
                    } else ""
                    rust("${parameter.memberName()}: self.${parameter.memberName()}$unwrap,")
                }
            })

        }
        Attribute.Derives(setOf(RuntimeType.Default)).render(it)
        it.rustTemplate(
            """
            pub struct Builder {
                #{builder_params:W}
            }
            
            impl Builder {
                #{impl_block:W}
            }
        """, "builder_params" to builderParams, "impl_block" to implBlock
        )
    }

    private fun params() = writable {
        endpointsModel.parameters.toList().forEach { parameter ->
            parameter.documentation.orNull()?.let { docs(it) }
            rust("pub ${parameter.memberName()}: #T,", parameter.symbol())
        }
    }

    fun endpointResolver(): RuntimeType = RuntimeType.forInlineFun("resolve_endpoint", endpointsModule) {
        val expandParams = writable {
            endpointsModel.parameters.toList().forEach {
                rust("let ${it.name.rustName()} = &params.${it.name.rustName()};")
            }
        }
        it.rustTemplate(
            """
            pub(crate) fn resolve_endpoint(params: &#{Params}) -> Result<#{Endpoint}, std::borrow::Cow<'static, str>> {
                #{expand_params:W}
                #{rules:W}
                
                return Err("No rules matched".into())
            }
        """,
            *scope,
            "Params" to endpointParamStruct(),
            "rules" to treeRule(listOf(), endpointsModel.rules, Context.empty()),
            "expand_params" to expandParams
        )
    }

    private fun treeRule(conditions: List<Condition>, rules: List<Rule>, ctx: Context) =
        generateCondition(conditions, { ctx ->
            {
                rules.forEach { rule ->
                    generateCondition(rule.conditions, { ctx -> ruleBody(rule, ctx) }, ctx)(this)
                }
            }
        }, ctx)

    private fun ruleBody(rule: Rule, ctx: Context): Writable = writable {
        when (rule) {
            is EndpointRule -> rust("return Ok(#W);", generateEndpoint(rule.endpoint, ctx))
            is ErrorRule -> rust("return Err(#W.into());", generateExpr(rule.error, ctx, Ownership.Owned))
            is TreeRule -> {
                rule.rules.forEach { subrule ->
                    generateCondition(
                        subrule.conditions,
                        { ctx -> ruleBody(subrule, ctx) },
                        ctx
                    )(this)
                }
            }
        }
    }

    private fun generateEndpoint(endpoint: Endpoint, ctx: Context): Writable = writable {
        rustTemplate(
            """#{Endpoint}::mutable(dbg!(#{uri:W}).parse().expect("invalid URI"))""",
            "uri" to generateExpr(endpoint.url, ctx, Ownership.Owned),
            *scope
        )
    }

    @Contract(pure = true)
    fun generateCondition(condition: List<Condition>, body: (Context) -> Writable, ctx: Context): Writable = writable {
        if (condition.isEmpty()) {
            body(ctx)(this)
        } else {
            val first = condition.first()
            rust("/* ${escape(first.toString())} */")
            val rest = condition.drop(1)
            val condName = nextName()
            val result = first.result.orNull()?.rustName()?.let { "let $it = $condName;" } ?: ""
            //val next = generateCondition(rest, body, ctx.withValue(first.fn))
            val fn = first.fn
            when {
                fn is IsSet -> rustTemplate(
                    "if let Some($condName) = #{target:W} { $result #{next:W} }",
                    "target" to generateExpr(fn.target, ctx, Ownership.Borrowed),
                    "next" to generateCondition(rest, body, ctx.withValue(fn.target, condName))
                )
                fn.type() is Type.Option -> rustTemplate(
                    "if let Some($condName) = #{target:W} { $result #{next:W} }",
                    "target" to generateExpr(fn, ctx, Ownership.Borrowed),
                    "next" to generateCondition(rest, body, ctx.withValue(fn, condName))
                )
                else -> rustTemplate(
                    """
                        let $condName = #{target:W};
                        if #{truthy:W} { $result #{next:W} }""",
                    "target" to generateExpr(first.fn, ctx, Ownership.Owned),
                    "truthy" to truthy(condName, first.fn.type()),
                    "next" to generateCondition(rest, body, ctx.withValue(first.fn, condName))
                )
            }
        }
    }

    fun truthy(name: String, type: Type): Writable = writable {
        when (type) {
            is Type.Bool -> rust(name)
            is Type.Option -> rust("let Some($name) = name")
            is Type.Str -> rust("${name}.len() > 0")
            else -> error("invalid: $type")
        }
    }

    private fun generateExpr(expr: Expr, ctx: Context, ownership: Ownership): Writable = writable {
        if (ctx.valueNumbering.contains(expr)) {
            if (expr.type() is Type.Bool) {
                rust("*")
            }
            rust(ctx.valueNumbering[expr]!!)
        } else {
            when (expr) {
                is Ref -> {
                    if (ownership == Ownership.Owned) {
                        rust("*")
                    }
                    rust(expr.name.rustName())
                }
                is Not -> rust("!#W", generateExpr(expr.target(), ctx, Ownership.Owned))
                is IsSet -> rust("#W.is_some()", generateExpr(expr.target, ctx, Ownership.Borrowed))
                is StringEquals -> rust("#W == #W", generateExpr(expr.left, ctx, Ownership.Borrowed), generateExpr(expr.right, ctx, Ownership.Borrowed))
                is BooleanEquals -> rust("#W == #W", generateExpr(expr.left, ctx, Ownership.Owned), generateExpr(expr.right, ctx, Ownership.Owned))
                is Literal -> {
                    when (expr.type()) {
                        is Type.Bool -> rust("${expr.source}")
                        is Type.Str -> rust("&${expr.source}")
                    }
                }
                is Template -> {
                    if (ownership == Ownership.Borrowed) {
                        rust("&")
                    }
                    this.generateTemplate(expr, ctx)
                }
                is ParseArn -> rustTemplate(
                    "#{endpoint_util}::Arn::parse(#{expr:W})",
                    "expr" to generateExpr(expr.target(), ctx, Ownership.Borrowed),
                    *scope
                )
                is GetAttr -> getAttr(expr, ctx)
                is PartitionFn -> rustTemplate(
                    "#{endpoint_util}::partition(&#{expr:W})",
                    "expr" to generateExpr(expr.target(), ctx, Ownership.Borrowed),
                    *scope
                )
                is IsValidHostLabel -> rustTemplate(
                    "#{endpoint_util}::is_valid_host_label(&#{host_label:W}, #{allow_dots:W})",
                    "host_label" to generateExpr(expr.hostLabel(), ctx, Ownership.Borrowed),
                    "allow_dots" to generateExpr(expr.allowDots(), ctx, Ownership.Owned),
                    *scope
                )
                else -> rust("todo!() /* ${escape(expr.toString())} */")
            }
        }
    }

    fun RustWriter.getAttr(getAttr: GetAttr, ctx: Context) {
        generateExpr(getAttr.target(), ctx, Ownership.Borrowed)(this)
        getAttr.path().toList().forEach { part ->
            when (part) {
                is GetAttr.Part.Key -> rust(".${part.key.rustName()}")
                is GetAttr.Part.Index -> rust(".get(${part.index})")
            }
        }
    }

    fun RustWriter.generateTemplate(template: Template, ctx: Context) {
        rust("{ let mut out = String::new(); ")
        rust("/* ${escape(template.toString())} */")
        for (part in template.parts) {
            when (part) {
                is Template.Literal -> rust("out.push_str(${part.value.dq()});")
                is Template.Dynamic -> rust("out.push_str(#W);", generateExpr(part.expr, ctx, Ownership.Borrowed))
            }
        }
        rust("out }")
    }
}


fun Identifier.rustName(): String {
    return this.toString().toSnakeCase()
}

fun Parameter.memberName(): String {
    return name.rustName()
}

fun Parameter.symbol(): Symbol {
    val rustType = when (this.type) {
        ParameterType.STRING -> RustType.String
        ParameterType.BOOLEAN -> RustType.Bool
    }
    return Symbol.builder().rustType(rustType).build().letIf(!this.isRequired) { it.makeOptional() }
}

object InlineAwsDependency {
    fun forRustFile(
        file: String,
        public: Boolean = false,
        vararg additionalDependency: RustDependency
    ): InlineDependency =
        InlineDependency.Companion.forRustFile(file, "aws-inlineable", public, *additionalDependency)
}
