package lin.rule.score

import lin.rule.parse.FieldParser
import lin.rule.parse.FieldSpec
import kotlin.reflect.KClass

/**
 * 评分算子：接收数据源提取出的值，并将其转换为最终分数。
 */
interface ScoreOperator<I : Any, P : Any> {
    val id: String
    val name: String
    val description: String
    val inputType: KClass<out I>
    val parameterType: KClass<out P>
    val paramSpecs: List<FieldSpec>

    fun score(input: I, parameter: P): Double
}

inline fun <reified I : Any, reified P : Any> scoreOperator(
    id: String,
    name: String,
    description: String = "",
    paramSpecs: List<FieldSpec>? = null,
    crossinline scorer: (I, P) -> Double
): ScoreOperator<I, P> {
    val inputKClass = I::class
    val parameterKClass = P::class
    val resolvedSpecs = paramSpecs ?: FieldParser.parse(P::class)
    return object : ScoreOperator<I, P> {
        override val id = id
        override val name = name
        override val description = description
        override val inputType = inputKClass
        override val parameterType = parameterKClass
        override val paramSpecs = resolvedSpecs

        override fun score(input: I, parameter: P): Double {
            return scorer(input, parameter)
        }
    }
}
