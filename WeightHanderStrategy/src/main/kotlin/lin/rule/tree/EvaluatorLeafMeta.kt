package lin.rule.tree

import lin.rule.build.ScoreEffectType
import lin.rule.condition.ConditionPayload
import lin.rule.parse.FieldConstraint
import lin.rule.parse.FieldSpec
import lin.rule.parse.FieldType
import lin.rule.score.ScoreEffect
import lin.rule.score.ScoreOperatorRegistry
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

internal object EvaluatorLeafMetaHelper : KoinComponent {
    val scoreOperatorRegistry: ScoreOperatorRegistry by inject()
}

const val EVALUATOR_LEAF_SCORE_EFFECT_TYPE_FIELD = "scoreEffectType"
const val EVALUATOR_LEAF_CONSTANT_SCORE_FIELD = "constantScore"
const val EVALUATOR_LEAF_SCORE_SOURCE_FIELD = "scoreSourceId"
const val EVALUATOR_LEAF_SCORE_OPERATOR_FIELD = "scoreOperatorId"
const val EVALUATOR_LEAF_MISS_VALUE_FIELD = "missValue"
const val SCORE_EFFECT_TYPE_CONSTANT = "constant"
const val SCORE_EFFECT_TYPE_SOURCE = "source"

/**
 * CONDITION / CONDITION_TREE 叶子固定绑定 ConstantScore。
 * 表单只展示 constantScore + missValue，无需评分类型选择器。
 */
val CONDITION_BUILT_IN_FIELDS: List<FieldSpec> = listOf(
    FieldSpec(
        propertyName = EVALUATOR_LEAF_CONSTANT_SCORE_FIELD,
        name = "固定分",
        description = "条件命中时的评分",
        typeStruct = FieldType.DoubleType,
        constraints = emptyList()
    ),
    FieldSpec(
        propertyName = EVALUATOR_LEAF_MISS_VALUE_FIELD,
        name = "未命中分数",
        description = "条件未命中时的评分",
        typeStruct = FieldType.DoubleType,
        constraints = emptyList()
    )
)

/** 按 ScoreEffectType 声明动态生成 builtInFields */
fun scoreEffectFieldsFor(type: ScoreEffectType): List<FieldSpec> = when (type) {
    ScoreEffectType.CONSTANT -> CONDITION_BUILT_IN_FIELDS
    ScoreEffectType.SOURCE -> listOf(
        FieldSpec(
            propertyName = EVALUATOR_LEAF_SCORE_EFFECT_TYPE_FIELD,
            name = "评分效应",
            description = "条件命中后如何产生分数",
            typeStruct = FieldType.SelectType("score_effect_types", FieldType.StringType),
            constraints = listOf(FieldConstraint.Required)
        ),
        FieldSpec(
            propertyName = EVALUATOR_LEAF_SCORE_SOURCE_FIELD,
            name = "评分数据源",
            description = "评分效应为数据源评分时使用",
            typeStruct = FieldType.SelectType("data_sources", FieldType.StringType),
            constraints = emptyList()
        ),
        FieldSpec(
            propertyName = EVALUATOR_LEAF_SCORE_OPERATOR_FIELD,
            name = "评分算子",
            description = "选中算子后，算子参数将由表单动态渲染",
            typeStruct = FieldType.SelectType("score_operators", FieldType.StringType),
            constraints = emptyList()
        ),
        FieldSpec(
            propertyName = EVALUATOR_LEAF_MISS_VALUE_FIELD,
            name = "未命中分数",
            description = "条件未命中时的评分",
            typeStruct = FieldType.DoubleType,
            constraints = emptyList()
        )
    )

    ScoreEffectType.NONE -> emptyList()
}

enum class EvaluatorLeafSourceType {
    RULE,
    CONDITION,
    CONDITION_TREE,
    ORTHOGONAL_RULE
}

sealed interface RulePayload {
    val args: Map<String, Any> get() = emptyMap()

    data class RuleRef(
        override val args: Map<String, Any> = emptyMap()
    ) : RulePayload

    data class OrthogonalRuleRef(
        val guardCondition: ConditionPayload? = null,
        val scoreEffect: ScoreEffect? = null,
        override val args: Map<String, Any> = emptyMap()
    ) : RulePayload
}

data class EvaluatorLeafMeta(
    val sourceType: EvaluatorLeafSourceType,
    val sourceId: String,
    val name: String?,
    val desc: String?,
    val builtInFields: List<FieldSpec> = emptyList(),
    val fields: List<FieldSpec>
)

data class EvaluatorLeafConfig(
    val nodeId: String,
    val sourceType: EvaluatorLeafSourceType,
    val sourceId: String,
    val scoreEffect: ScoreEffect? = null,
    val args: Map<String, Any> = emptyMap(),
    val guardCondition: ConditionPayload? = null,
    val rulePayload: RulePayload? = null
) {
    /** 聚合后的有效规则 Payload，优先使用显式 rulePayload，并在为 null 时兜底从老字段自动合成 */
    val effectiveRulePayload: RulePayload
        get() = rulePayload ?: when (sourceType) {
            EvaluatorLeafSourceType.RULE -> RulePayload.RuleRef(args)
            EvaluatorLeafSourceType.ORTHOGONAL_RULE -> RulePayload.OrthogonalRuleRef(guardCondition, scoreEffect, args)
            else -> RulePayload.RuleRef(emptyMap())
        }
}

/**
 * 兼容性解析函数：
 * 如果存在 guardCondition 则直接返回该 ConditionPayload；
 * 否则利用 sourceId 与 args 兜底构建 ConditionRef。
 */
fun EvaluatorLeafConfig.resolveConditionPayload(): ConditionPayload {
    if (guardCondition != null) return guardCondition
    return ConditionPayload.ConditionRef(conditionId = sourceId, args = args)
}

fun buildEvaluatorLeafConfig(
    nodeId: String,
    selectedLeaf: EvaluatorLeafMeta,
    existing: EvaluatorLeafConfig?,
    formValues: Map<String, Any>
): EvaluatorLeafConfig {
    val builtInFieldNames = selectedLeaf.builtInFields.map { it.propertyName }.toSet()
    val consumedOperatorKeys = operatorParamKeys(formValues, existing?.scoreEffect)
    val extArgs = formValues.filterKeys { it !in builtInFieldNames && it !in consumedOperatorKeys }
    val scoreEffect = buildScoreEffect(formValues, existing?.scoreEffect)

    val guardCondition = existing?.guardCondition ?: if (selectedLeaf.sourceType == EvaluatorLeafSourceType.CONDITION) {
        ConditionPayload.ConditionRef(conditionId = selectedLeaf.sourceId, args = extArgs)
    } else null

    return EvaluatorLeafConfig(
        nodeId = nodeId,
        sourceType = selectedLeaf.sourceType,
        sourceId = selectedLeaf.sourceId,
        scoreEffect = scoreEffect,
        args = extArgs,
        guardCondition = guardCondition,
        rulePayload = when (selectedLeaf.sourceType) {
            EvaluatorLeafSourceType.RULE -> RulePayload.RuleRef(extArgs)
            EvaluatorLeafSourceType.ORTHOGONAL_RULE -> RulePayload.OrthogonalRuleRef(
                guardCondition,
                scoreEffect,
                extArgs
            )

            else -> null
        }
    )
}


fun EvaluatorLeafConfig.valueOfField(propertyName: String): Any? {
    return when (propertyName) {
        EVALUATOR_LEAF_SCORE_EFFECT_TYPE_FIELD -> when (scoreEffect) {
            is ScoreEffect.SourceScore -> SCORE_EFFECT_TYPE_SOURCE
            else -> SCORE_EFFECT_TYPE_CONSTANT
        }

        EVALUATOR_LEAF_CONSTANT_SCORE_FIELD -> (scoreEffect as? ScoreEffect.ConstantScore)?.value
        EVALUATOR_LEAF_MISS_VALUE_FIELD -> scoreEffect?.let {
            when (it) {
                is ScoreEffect.ConstantScore -> it.missValue
                is ScoreEffect.SourceScore -> it.missValue
            }
        }

        EVALUATOR_LEAF_SCORE_SOURCE_FIELD -> (scoreEffect as? ScoreEffect.SourceScore)?.sourceId
        EVALUATOR_LEAF_SCORE_OPERATOR_FIELD -> (scoreEffect as? ScoreEffect.SourceScore)?.operatorId
        else -> args[propertyName] ?: (scoreEffect as? ScoreEffect.SourceScore)?.args?.get(propertyName)
    }
}

private fun buildScoreEffect(
    formValues: Map<String, Any>,
    existing: ScoreEffect?
): ScoreEffect? {
    val type = (formValues[EVALUATOR_LEAF_SCORE_EFFECT_TYPE_FIELD] as? String)
        ?: when (existing) {
            is ScoreEffect.SourceScore -> SCORE_EFFECT_TYPE_SOURCE
            else -> SCORE_EFFECT_TYPE_CONSTANT
        }

    return when (type) {
        SCORE_EFFECT_TYPE_SOURCE -> {
            val sourceId = formValues[EVALUATOR_LEAF_SCORE_SOURCE_FIELD] as? String
                ?: (existing as? ScoreEffect.SourceScore)?.sourceId
                ?: return null
            val operatorId = formValues[EVALUATOR_LEAF_SCORE_OPERATOR_FIELD] as? String
                ?: (existing as? ScoreEffect.SourceScore)?.operatorId
                ?: return null
            val operator = EvaluatorLeafMetaHelper.scoreOperatorRegistry.find(operatorId)
            val paramKeys = operator?.paramSpecs?.map { it.propertyName }?.toSet() ?: emptySet()
            val oldArgs = (existing as? ScoreEffect.SourceScore)?.args ?: emptyMap()
            val scoreArgs = formValues.filterKeys { it in paramKeys }
            val missValue = formValues.doubleValue(EVALUATOR_LEAF_MISS_VALUE_FIELD)
                ?: (existing as? ScoreEffect.SourceScore)?.missValue
                ?: 0.0
            ScoreEffect.SourceScore(sourceId, operatorId, missValue, oldArgs + scoreArgs)
        }

        else -> ScoreEffect.ConstantScore(
            value = formValues.doubleValue(EVALUATOR_LEAF_CONSTANT_SCORE_FIELD)
                ?: (existing as? ScoreEffect.ConstantScore)?.value
                ?: 0.0,
            missValue = formValues.doubleValue(EVALUATOR_LEAF_MISS_VALUE_FIELD)
                ?: (existing as? ScoreEffect.ConstantScore)?.missValue
                ?: 0.0
        )
    }
}

/**
 * 计算当前选中算子在 formValues 中对应的参数键集合。
 * 用于在 [buildEvaluatorLeafConfig] 中排除已被评分效应消费的键，避免重复进入 args。
 */
private fun operatorParamKeys(
    formValues: Map<String, Any>,
    existing: ScoreEffect?
): Set<String> {
    val type = (formValues[EVALUATOR_LEAF_SCORE_EFFECT_TYPE_FIELD] as? String)
        ?: when (existing) {
            is ScoreEffect.SourceScore -> SCORE_EFFECT_TYPE_SOURCE
            else -> return emptySet()
        }
    if (type != SCORE_EFFECT_TYPE_SOURCE) return emptySet()

    val operatorId = formValues[EVALUATOR_LEAF_SCORE_OPERATOR_FIELD] as? String
        ?: (existing as? ScoreEffect.SourceScore)?.operatorId
        ?: return emptySet()

    return EvaluatorLeafMetaHelper.scoreOperatorRegistry.find(operatorId)?.paramSpecs?.map { it.propertyName }?.toSet()
        ?: emptySet()
}

private fun Map<String, Any>.doubleValue(propertyName: String): Double? {
    return when (val value = this[propertyName]) {
        is Double -> value
        is Number -> value.toDouble()
        else -> null
    }
}
