package lin.rule.orthogonal

import lin.rule.parse.FieldParser
import lin.rule.parse.FieldSpec
import kotlin.reflect.KType
import kotlin.reflect.typeOf

object OperatorCategories {
    const val NUMBER = "数值"
    const val COMPARE = "比较"
    const val COLLECTION = "集合"
    const val EXISTENCE = "存在性"
    const val STATE = "状态"
}

/**
 * 比较算子：接收输入值进行逻辑运算判定。
 */
interface Operator<I : Any, P : Any> {
    val id: String
    val name: String
    val description: String
    val categories: Set<String>
    val inputType: KType
    val parameterType: kotlin.reflect.KClass<out P>
    val paramSpecs: List<FieldSpec>

    fun evaluate(input: I, parameter: P): Boolean
}

/**
 * 算子 DSL 构建器
 */
inline fun <reified I : Any, reified P : Any> operator(
    id: String,
    name: String,
    description: String = "",
    categories: Set<String> = emptySet(),
    paramSpecs: List<FieldSpec>? = null,
    crossinline evaluator: (I, P) -> Boolean
): Operator<I, P> {
    val resolvedSpecs = paramSpecs ?: FieldParser.parse(P::class)
    return object : Operator<I, P> {
        override val id = id
        override val name = name
        override val description = description
        override val categories = categories
        override val inputType: KType = typeOf<I>()
        override val parameterType = P::class
        override val paramSpecs = resolvedSpecs

        override fun evaluate(input: I, parameter: P): Boolean {
            return evaluator(input, parameter)
        }
    }
}
