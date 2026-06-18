package lin.rule.orthogonal

import lin.rule.context.RuleContext
import lin.rule.context.RuleEnv
import lin.rule.parse.FieldParser
import lin.rule.parse.FieldSpec
import kotlin.reflect.KType
import kotlin.reflect.typeOf

/**
 * 转换器 (管道中间件)：对数据流进行过滤、映射、聚合或投影。
 */
interface Transform<In : Any, Out : Any> {
    val id: String
    val name: String
    val description: String
    val inputType: KType
    val outputType: KType
    val fields: List<FieldSpec>

    context(env: RuleEnv)
    fun transform(input: In, context: RuleContext, args: Map<String, Any> = emptyMap()): Out
}

/**
 * 无需转换参数的简单转换器 DSL 构建器
 */
inline fun <reified In : Any, reified Out : Any> transform(
    id: String,
    name: String,
    description: String = "",
    fields: List<FieldSpec> = emptyList(),
    crossinline transformer: context(RuleEnv) RuleContext.(In, Map<String, Any>) -> Out
): Transform<In, Out> = object : Transform<In, Out> {
    override val id = id
    override val name = name
    override val description = description
    override val inputType: KType = typeOf<In>()
    override val outputType: KType = typeOf<Out>()
    override val fields = fields

    context(env: RuleEnv)
    override fun transform(input: In, context: RuleContext, args: Map<String, Any>): Out {
        return context.transformer(input, args)
    }
}

/**
 * 带有强类型参数的转换器 DSL 构建器
 */
@JvmName("transformParameterized")
inline fun <reified In : Any, reified Out : Any, reified P : Any> transform(
    id: String,
    name: String,
    description: String = "",
    paramSpecs: List<FieldSpec>? = null,
    crossinline transformer: context(RuleEnv) RuleContext.(In, P) -> Out
): Transform<In, Out> {
    val resolvedSpecs = paramSpecs ?: FieldParser.parse(P::class)
    return object : Transform<In, Out> {
        override val id = id
        override val name = name
        override val description = description
        override val inputType: KType = typeOf<In>()
        override val outputType: KType = typeOf<Out>()
        override val fields = resolvedSpecs

        context(env: RuleEnv)
        override fun transform(input: In, context: RuleContext, args: Map<String, Any>): Out {
            val parameter = lin.rule.parse.mapToRuleArgs(args, P::class)
            return context.transformer(input, parameter)
        }
    }
}
