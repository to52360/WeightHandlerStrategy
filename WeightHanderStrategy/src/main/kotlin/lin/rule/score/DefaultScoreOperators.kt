package lin.rule.score

import lin.rule.parse.FieldConstraint
import lin.rule.parse.FieldSpec
import lin.rule.parse.FieldType

object NoScoreParams

data class LinearScoreParams(
    val factor: Double,
    val offset: Double = 0.0
)

data class ReverseLinearScoreParams(
    val pivot: Double,
    val factor: Double,
    val offset: Double = 0.0
)

val IdentityScoreOperator = scoreOperator<Number, NoScoreParams>(
    id = "identity",
    name = "直接作为分数",
    description = "将数据源提取出的数值直接作为分数"
) { input, _ ->
    input.toDouble()
}

val LinearScoreOperator = scoreOperator<Number, LinearScoreParams>(
    id = "linear",
    name = "线性评分",
    description = "score = input * factor + offset",
    paramSpecs = listOf(
        FieldSpec(
            propertyName = "factor",
            name = "系数",
            description = "输入数值的乘法系数",
            typeStruct = FieldType.DoubleType,
            constraints = listOf(FieldConstraint.Required)
        ),
        FieldSpec(
            propertyName = "offset",
            name = "偏移",
            description = "最终分数的加法偏移",
            typeStruct = FieldType.DoubleType,
            constraints = emptyList()
        )
    )
) { input, params ->
    input.toDouble() * params.factor + params.offset
}

val ReverseLinearScoreOperator = scoreOperator<Number, ReverseLinearScoreParams>(
    id = "reverse_linear",
    name = "反向线性评分",
    description = "score = (pivot - input) * factor + offset",
    paramSpecs = listOf(
        FieldSpec(
            propertyName = "pivot",
            name = "基准值",
            description = "输入值从该基准值反向计算分数",
            typeStruct = FieldType.DoubleType,
            constraints = listOf(FieldConstraint.Required)
        ),
        FieldSpec(
            propertyName = "factor",
            name = "系数",
            description = "反向差值的乘法系数",
            typeStruct = FieldType.DoubleType,
            constraints = listOf(FieldConstraint.Required)
        ),
        FieldSpec(
            propertyName = "offset",
            name = "偏移",
            description = "最终分数的加法偏移",
            typeStruct = FieldType.DoubleType,
            constraints = emptyList()
        )
    )
) { input, params ->
    (params.pivot - input.toDouble()) * params.factor + params.offset
}

// ARCH-UNSETTLED(score-effect, U-001): 评分算子暂以内置注册表提供，后续是否升级为 SPI Provider 仍待评估 | next: 当出现外部插件评分算子需求时抽取 ScoreOperatorProvider
object DefaultScoreOperators {
    val all: Map<String, ScoreOperator<*, *>> = listOf(
        IdentityScoreOperator,
        LinearScoreOperator,
        ReverseLinearScoreOperator
    ).associateBy { it.id }
}
