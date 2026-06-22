package lin.tree_config.ui.strategy

import lin.rule.score.ScoreEffect
import lin.rule.score.ScoreOperatorRegistry
import lin.rule.tree.*

/** 表单字段名 → ScoreEffect 领域对象转换（UI 层职责） */
fun buildScoreEffect(
    formValues: Map<String, Any>,
    existing: ScoreEffect?,
    scoreOperatorRegistry: ScoreOperatorRegistry
): ScoreEffect? {
    val isSource = (formValues[EVALUATOR_LEAF_SCORE_EFFECT_TYPE_FIELD] as? String) == "source"
            || existing is ScoreEffect.SourceScore

    return if (isSource) {
        val sourceId = formValues[EVALUATOR_LEAF_SCORE_SOURCE_FIELD] as? String
            ?: (existing as? ScoreEffect.SourceScore)?.sourceId
            ?: return null
        val operatorId = formValues[EVALUATOR_LEAF_SCORE_OPERATOR_FIELD] as? String
            ?: (existing as? ScoreEffect.SourceScore)?.operatorId
            ?: return null
        val operator = scoreOperatorRegistry.find(operatorId)
        val paramKeys = operator?.paramSpecs?.map { it.propertyName }?.toSet() ?: emptySet()
        val oldArgs = (existing as? ScoreEffect.SourceScore)?.operatorArgs ?: emptyMap()
        val scoreArgs = formValues.filterKeys { it in paramKeys }
        val missValue = formValues.doubleValue(EVALUATOR_LEAF_MISS_VALUE_FIELD)
            ?: (existing as? ScoreEffect.SourceScore)?.missValue
            ?: 0.0
        ScoreEffect.SourceScore(
            sourceId = sourceId,
            transforms = emptyList(),
            operatorId = operatorId,
            operatorArgs = oldArgs + scoreArgs,
            missValue = missValue
        )
    } else {
        ScoreEffect.ConstantScore(
            value = formValues.doubleValue(EVALUATOR_LEAF_CONSTANT_SCORE_FIELD)
                ?: (existing as? ScoreEffect.ConstantScore)?.value
                ?: 0.0,
            missValue = formValues.doubleValue(EVALUATOR_LEAF_MISS_VALUE_FIELD)
                ?: (existing as? ScoreEffect.ConstantScore)?.missValue
                ?: 0.0
        )
    }
}

/** 从 formValues + scoreEffect 反查属性值，用于表单预填 */
fun EvaluatorLeafConfig.valueOfField(propertyName: String): Any? {
    val scoreable = this as? Scoreable ?: return args[propertyName]
    return valueOfScoreEffectField(scoreable.scoreEffect, args, propertyName)
}

private fun valueOfScoreEffectField(scoreEffect: ScoreEffect, args: Map<String, Any>, propertyName: String): Any? {
    return when (propertyName) {
        EVALUATOR_LEAF_SCORE_EFFECT_TYPE_FIELD -> when (scoreEffect) {
            is ScoreEffect.SourceScore -> "source"
            else -> "constant"
        }

        EVALUATOR_LEAF_CONSTANT_SCORE_FIELD -> (scoreEffect as? ScoreEffect.ConstantScore)?.value
        EVALUATOR_LEAF_MISS_VALUE_FIELD -> scoreEffect.missValue
        EVALUATOR_LEAF_SCORE_SOURCE_FIELD -> (scoreEffect as? ScoreEffect.SourceScore)?.sourceId
        EVALUATOR_LEAF_SCORE_OPERATOR_FIELD -> (scoreEffect as? ScoreEffect.SourceScore)?.operatorId
        else -> args[propertyName] ?: (scoreEffect as? ScoreEffect.SourceScore)?.operatorArgs?.get(propertyName)
    }
}

private fun Map<String, Any>.doubleValue(propertyName: String): Double? {
    return when (val value = this[propertyName]) {
        is Double -> value
        is Number -> value.toDouble()
        else -> null
    }
}
