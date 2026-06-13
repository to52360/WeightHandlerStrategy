package lin.rule.tree

import lin.rule.parse.FieldConstraint
import lin.rule.parse.FieldSpec
import lin.rule.parse.FieldType

const val EVALUATOR_LEAF_WEIGHT_FIELD = "weight"
const val EVALUATOR_LEAF_MISMATCHED_WEIGHT_FIELD = "mismatchedWeight"
const val EVALUATOR_LEAF_WEIGHT_SOURCE_FIELD = "weightSourceId"
const val EVALUATOR_LEAF_MISMATCHED_WEIGHT_SOURCE_FIELD = "mismatchedWeightSourceId"

val EVALUATOR_LEAF_BUILT_IN_FIELDS: List<FieldSpec> = listOf(
    FieldSpec(
        propertyName = EVALUATOR_LEAF_WEIGHT_FIELD,
        name = "命中权重",
        description = "叶子逻辑匹配成功时的基础权重",
        typeStruct = FieldType.DoubleType,
        constraints = listOf(FieldConstraint.Required)
    ),
    FieldSpec(
        propertyName = EVALUATOR_LEAF_MISMATCHED_WEIGHT_FIELD,
        name = "未命中权重",
        description = "叶子逻辑匹配失败时的基础权重",
        typeStruct = FieldType.DoubleType,
        constraints = listOf(FieldConstraint.Required)
    ),
    FieldSpec(
        propertyName = EVALUATOR_LEAF_WEIGHT_SOURCE_FIELD,
        name = "命中权重乘数数据源",
        description = "命中时，权重乘以该数据源提取的数值（可选）",
        typeStruct = FieldType.SelectType("data_sources", FieldType.StringType),
        constraints = emptyList()
    ),
    FieldSpec(
        propertyName = EVALUATOR_LEAF_MISMATCHED_WEIGHT_SOURCE_FIELD,
        name = "未命中权重乘数数据源",
        description = "未命中时，权重乘以该数据源提取 of 的数值（可选）",
        typeStruct = FieldType.SelectType("data_sources", FieldType.StringType),
        constraints = emptyList()
    )
)

enum class EvaluatorLeafSourceType {
    RULE,
    CONDITION,
    CONDITION_TREE
}

data class EvaluatorLeafMeta(
    val sourceType: EvaluatorLeafSourceType,
    val sourceId: String,
    val name: String?,
    val desc: String?,
    val builtInFields: List<FieldSpec> = EVALUATOR_LEAF_BUILT_IN_FIELDS,
    val fields: List<FieldSpec>
)

data class EvaluatorLeafConfig(
    val nodeId: String,
    val sourceType: EvaluatorLeafSourceType,
    val sourceId: String,
    val weight: Double,
    val mismatchedWeight: Double,
    val args: Map<String, Any> = emptyMap(),
    val weightSourceId: String? = null,
    val mismatchedWeightSourceId: String? = null
)

private val EVALUATOR_LEAF_BUILT_IN_FIELD_NAMES: Set<String> =
    EVALUATOR_LEAF_BUILT_IN_FIELDS.mapTo(linkedSetOf()) { it.propertyName }

fun buildEvaluatorLeafConfig(
    nodeId: String,
    selectedLeaf: EvaluatorLeafMeta,
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
        weightSourceId = formValues[EVALUATOR_LEAF_WEIGHT_SOURCE_FIELD] as? String ?: existing?.weightSourceId,
        mismatchedWeightSourceId = formValues[EVALUATOR_LEAF_MISMATCHED_WEIGHT_SOURCE_FIELD] as? String
            ?: existing?.mismatchedWeightSourceId,
        args = formValues.filterKeys { it !in EVALUATOR_LEAF_BUILT_IN_FIELD_NAMES }
    )
}

fun EvaluatorLeafConfig.valueOfField(propertyName: String): Any? {
    return when (propertyName) {
        EVALUATOR_LEAF_WEIGHT_FIELD -> weight
        EVALUATOR_LEAF_MISMATCHED_WEIGHT_FIELD -> mismatchedWeight
        EVALUATOR_LEAF_WEIGHT_SOURCE_FIELD -> weightSourceId
        EVALUATOR_LEAF_MISMATCHED_WEIGHT_SOURCE_FIELD -> mismatchedWeightSourceId
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
