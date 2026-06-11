package lin.rule.condition.orthogonal

import club.xiaojiawei.hsscriptcardsdk.enums.CardRaceEnum
import kotlin.reflect.KClass

/**
 * 比较算子：纯逻辑运算逻辑，与特定的业务数据提取完全无关。
 * 算子定义了所需的参数类型 [P]，由装配引擎在编译期进行统一校验与解析。
 */
interface Operator<I : Any, P : Any> {
    val id: String
    val name: String
    val description: String
    val categories: Set<String>       // 用于 UI 过滤推荐，如 {"数值", "比较"}
    val inputType: KClass<out I>      // 输入数据类型
    val parameterType: KClass<out P>  // 参数数据类型

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
    crossinline evaluator: (I, P) -> Boolean
): Operator<I, P> = object : Operator<I, P> {
    override val id = id
    override val name = name
    override val description = description
    override val categories = categories
    override val inputType = I::class
    override val parameterType = P::class

    override fun evaluate(input: I, parameter: P): Boolean {
        return evaluator(input, parameter)
    }
}

// ==========================================
// 示例一：大于等于算子
// ==========================================
data class GteParams(val threshold: Int)

val GreaterThanOrEqualOp = operator<Int, GteParams>(
    id = "gte",
    name = "大于等于",
    description = "判断输入数值是否大于等于指定的阈值",
    categories = setOf("数值", "比较")
) { input, params ->
    input >= params.threshold
}

// ==========================================
// 示例二：集合包含算子
// ==========================================
data class ContainsRaceParams(val targetRace: CardRaceEnum)

val ContainsRaceOp = operator<Set<CardRaceEnum>, ContainsRaceParams>(
    id = "contains_race",
    name = "包含种族",
    description = "判断输入种族集合中是否包含指定的种族",
    categories = setOf("集合", "存在性")
) { input, params ->
    input.contains(params.targetRace)
}
