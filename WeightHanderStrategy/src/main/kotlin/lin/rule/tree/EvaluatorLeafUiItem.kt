package lin.rule.tree

import lin.rule.parse.FieldConstraint
import lin.rule.parse.FieldType
import lin.rule.parse.RuleFieldSpec

const val EVALUATOR_LEAF_WEIGHT_FIELD = "weight"
const val EVALUATOR_LEAF_MISMATCHED_WEIGHT_FIELD = "mismatchedWeight"

val EVALUATOR_LEAF_BUILT_IN_FIELDS: List<RuleFieldSpec> = listOf(
    RuleFieldSpec(
        propertyName = EVALUATOR_LEAF_WEIGHT_FIELD,
        name = "命中权重",
        description = "叶子逻辑匹配成功时的基础权重",
        typeStruct = FieldType.DoubleType,
        constraints = listOf(FieldConstraint.Required)
    ),
    RuleFieldSpec(
        propertyName = EVALUATOR_LEAF_MISMATCHED_WEIGHT_FIELD,
        name = "未命中权重",
        description = "叶子逻辑匹配失败时的基础权重",
        typeStruct = FieldType.DoubleType,
        constraints = listOf(FieldConstraint.Required)
    )
)

enum class EvaluatorLeafSourceType {
    RULE,
    CONDITION,
    CONDITION_TREE
}

data class EvaluatorLeafUiItem(
    val sourceType: EvaluatorLeafSourceType,
    val sourceId: String,
    val name: String?,
    val desc: String?,
    val builtInFields: List<RuleFieldSpec> = EVALUATOR_LEAF_BUILT_IN_FIELDS,
    val fields: List<RuleFieldSpec>
)

data class EvaluatorLeafConfig(
    val nodeId: String,
    val sourceType: EvaluatorLeafSourceType,
    val sourceId: String,
    val weight: Double,
    val mismatchedWeight: Double,
    val args: Map<String, Any> = emptyMap()
)

private val EVALUATOR_LEAF_BUILT_IN_FIELD_NAMES: Set<String> =
    EVALUATOR_LEAF_BUILT_IN_FIELDS.mapTo(linkedSetOf()) { it.propertyName }

fun buildEvaluatorLeafConfig(
    nodeId: String,
    selectedLeaf: EvaluatorLeafUiItem,
    existing: EvaluatorLeafConfig?,
    formValues: Map<String, Any>
): EvaluatorLeafConfig {
    return EvaluatorLeafConfig(
        nodeId = nodeId,
        sourceType = selectedLeaf.sourceType,
        sourceId = selectedLeaf.sourceId,
        weight = formValues.doubleValue(EVALUATOR_LEAF_WEIGHT_FIELD) ?: existing?.weight ?: 0.0,
        mismatchedWeight = formValues.doubleValue(EVALUATOR_LEAF_MISMATCHED_WEIGHT_FIELD)
            ?: existing?.mismatchedWeight
            ?: 0.0,
        args = formValues.filterKeys { it !in EVALUATOR_LEAF_BUILT_IN_FIELD_NAMES }
    )
}

fun EvaluatorLeafConfig.valueOfField(propertyName: String): Any? {
    return when (propertyName) {
        EVALUATOR_LEAF_WEIGHT_FIELD -> weight
        EVALUATOR_LEAF_MISMATCHED_WEIGHT_FIELD -> mismatchedWeight
        else -> args[propertyName]
    }
}

private fun Map<String, Any>.doubleValue(propertyName: String): Double? {
    return when (val value = this[propertyName]) {
        is Double -> value
        is Number -> value.toDouble()
        else -> null
    }
}
