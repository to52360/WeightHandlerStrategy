package lin.rule.condition.orthogonal

import club.xiaojiawei.hsscriptcardsdk.enums.CardRaceEnum
import lin.rule.parse.FieldConstraint
import lin.rule.parse.FieldParser
import lin.rule.parse.FieldSpec
import lin.rule.parse.FieldType
import kotlin.reflect.KClass

object OperatorCategories {
    const val NUMBER = "数值"
    const val COMPARE = "比较"
    const val COLLECTION = "集合"
    const val EXISTENCE = "存在性"
    const val STATE = "状态"
}

/**
 * 比较算子：纯逻辑运算逻辑，与特定的业务数据提取完全无关。
 * 算子定义了所需的参数类型 [P]，由装配引擎在编译期进行统一校验与解析。
 */
interface Operator<I : Any, P : Any> {
    // ARCH-UNSETTLED(orthogonal-condition, U-002): Jackson参数在反序列化时的类型安全匹配规范，特别是类型擦除情况 | next: 在集成测试中全面校验复杂参数类型的反序列化
    val id: String
    val name: String
    val description: String
    val categories: Set<String>       // 用于 UI 过滤推荐，如 {"数值", "比较"}
    val inputType: KClass<out I>      // 输入数据类型
    val parameterType: KClass<out P>  // 参数数据类型
    val paramSpecs: List<FieldSpec>   // 🌟 算子参数的元数据约束描述

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
    paramSpecs: List<FieldSpec>? = null, // 允许显式传入以覆盖反射解析
    crossinline evaluator: (I, P) -> Boolean
): Operator<I, P> {
    val resolvedSpecs = paramSpecs ?: FieldParser.parse(P::class)
    val inputKClass = I::class
    val parameterKClass = P::class
    return object : Operator<I, P> {
        override val id = id
        override val name = name
        override val description = description
        override val categories = categories
        override val inputType = inputKClass
        override val parameterType = parameterKClass
        override val paramSpecs = resolvedSpecs

        override fun evaluate(input: I, parameter: P): Boolean {
            return evaluator(input, parameter)
        }
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
    categories = setOf(OperatorCategories.NUMBER, OperatorCategories.COMPARE),
    paramSpecs = listOf(
        FieldSpec(
            propertyName = "threshold",
            name = "阈值",
            description = "比较的阈值",
            typeStruct = FieldType.IntType,
            constraints = listOf(FieldConstraint.Required, FieldConstraint.IntRange(0, 100))
        )
    )
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
    categories = setOf(OperatorCategories.COLLECTION, OperatorCategories.EXISTENCE)
) { input, params ->
    input.contains(params.targetRace)
}
