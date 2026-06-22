package lin.rule.tree

import lin.rule.condition.ConditionPayload
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
const val EVALUATOR_LEAF_GUARD_MISS_BEHAVIOR_FIELD = "guardMissBehavior"

/**
 * 守卫未命中时的行为策略。
 * - [SCORE]：给 missValue 兜底分，继续评估其他节点（默认行为，向后兼容）
 * - [PRUNE]：控制流剪枝，终止整棵评估树
 */
enum class GuardMissBehavior { SCORE, PRUNE }

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

private val SCORE_EFFECT_SOURCE_BUILT_IN_FIELDS: List<FieldSpec> = listOf(
    FieldSpec(
        propertyName = EVALUATOR_LEAF_SCORE_EFFECT_TYPE_FIELD,
        name = "评分效应",
        description = "条件命中后如何产生分数",
        typeStruct = FieldType.SelectType("score_effect_types", FieldType.StringType),
        constraints = listOf(lin.rule.parse.FieldConstraint.Required)
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

/** 按 ScoreEffect 子类类型生成 builtInFields */
fun scoreEffectFieldsFor(effect: ScoreEffect): List<FieldSpec> = when (effect) {
    is ScoreEffect.ConstantScore -> CONDITION_BUILT_IN_FIELDS
    is ScoreEffect.SourceScore -> SCORE_EFFECT_SOURCE_BUILT_IN_FIELDS
}

enum class EvaluatorLeafCategory { CONDITION, RULE }

/** 标记接口，用于统一判断任意正交类型（正交条件或正交规则）。 */
sealed interface OrthogonalKind

sealed class EvaluatorLeafKind(
    @com.fasterxml.jackson.annotation.JsonValue
    val typeName: String
) {
    abstract val category: EvaluatorLeafCategory

    companion object {
        @com.fasterxml.jackson.annotation.JsonCreator
        @JvmStatic
        fun fromTypeName(typeName: String): EvaluatorLeafKind = when (typeName) {
            "CONDITION" -> Condition.Plain
            "ORTHOGONAL_CONDITION" -> Condition.Orthogonal
            "CONDITION_TREE" -> Condition.Tree
            "RULE" -> Rule.Coded
            "ORTHOGONAL_RULE" -> Rule.Orthogonal
            else -> throw IllegalArgumentException("Unknown EvaluatorLeafKind typeName: $typeName")
        }
    }

    sealed class Condition(typeName: String) : EvaluatorLeafKind(typeName) {
        override val category = EvaluatorLeafCategory.CONDITION

        object Plain : Condition("CONDITION")
        object Orthogonal : Condition("ORTHOGONAL_CONDITION"), OrthogonalKind
        object Tree : Condition("CONDITION_TREE")
    }

    sealed class Rule(typeName: String) : EvaluatorLeafKind(typeName) {
        override val category = EvaluatorLeafCategory.RULE

        object Coded : Rule("RULE")
        object Orthogonal : Rule("ORTHOGONAL_RULE"), OrthogonalKind
    }
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
    val kind: EvaluatorLeafKind,
    val sourceId: String,
    val name: String?,
    val desc: String?,
    val builtInFields: List<FieldSpec> = emptyList(),
    val fields: List<FieldSpec>
)

sealed class EvaluatorLeafConfig {
    abstract val nodeId: String
    abstract val kind: EvaluatorLeafKind
    abstract val sourceId: String
    abstract val args: Map<String, Any>
    abstract val guardMissBehavior: GuardMissBehavior
}

/** 标记接口：具有 ScoreEffect 评分效应的叶子节点 */
sealed interface Scoreable {
    val scoreEffect: ScoreEffect
}

/** 标记接口：具有显式守卫条件的叶子节点（仅 Rule 类型；Condition 类型通过 sourceId 兜底） */
sealed interface Guarded {
    val guardCondition: ConditionPayload?
}

data class RuleLeafConfig(
    override val nodeId: String,
    override val sourceId: String,
    override val args: Map<String, Any> = emptyMap(),
    override val guardCondition: ConditionPayload? = null,
    override val scoreEffect: ScoreEffect = ScoreEffect.ConstantScore(0.0),
    override val guardMissBehavior: GuardMissBehavior = GuardMissBehavior.SCORE
) : EvaluatorLeafConfig(), Scoreable, Guarded {
    override val kind: EvaluatorLeafKind = EvaluatorLeafKind.Rule.Coded
    val rulePayload: RulePayload.RuleRef = RulePayload.RuleRef(args)
}

data class OrthogonalRuleLeafConfig(
    override val nodeId: String,
    override val sourceId: String = "orthogonal_rule",
    override val guardCondition: ConditionPayload? = null,
    override val scoreEffect: ScoreEffect = ScoreEffect.SourceScore(sourceId = "", operatorId = "", missValue = 0.0),
    override val args: Map<String, Any> = emptyMap(),
    override val guardMissBehavior: GuardMissBehavior = GuardMissBehavior.SCORE
) : EvaluatorLeafConfig(), Scoreable, Guarded {
    override val kind: EvaluatorLeafKind = EvaluatorLeafKind.Rule.Orthogonal
    val rulePayload: RulePayload.OrthogonalRuleRef = RulePayload.OrthogonalRuleRef(guardCondition, scoreEffect, args)
}

/** 正交条件：guard 即[比较算子]管道，固定非空；含叶子态评分 */
data class OrthogonalConditionLeafConfig(
    override val nodeId: String,
    override val sourceId: String = "orthogonal_condition",
    override val guardCondition: ConditionPayload.PipelineRef,
    override val scoreEffect: ScoreEffect = ScoreEffect.ConstantScore(0.0),
    override val args: Map<String, Any> = emptyMap(),
    override val guardMissBehavior: GuardMissBehavior = GuardMissBehavior.SCORE
) : EvaluatorLeafConfig(), Scoreable, Guarded {
    override val kind: EvaluatorLeafKind = EvaluatorLeafKind.Condition.Orthogonal
}

/** 普通(编码)条件：条件本体由 sourceId 索引的条件实现提供，无需额外守卫 */
data class ConditionLeafConfig(
    override val nodeId: String,
    override val sourceId: String,
    override val scoreEffect: ScoreEffect = ScoreEffect.ConstantScore(0.0),
    override val args: Map<String, Any> = emptyMap(),
    override val guardMissBehavior: GuardMissBehavior = GuardMissBehavior.SCORE
) : EvaluatorLeafConfig(), Scoreable {
    override val kind: EvaluatorLeafKind = EvaluatorLeafKind.Condition.Plain
}

data class ConditionTreeLeafConfig(
    override val nodeId: String,
    override val sourceId: String,
    override val scoreEffect: ScoreEffect = ScoreEffect.ConstantScore(0.0),
    override val args: Map<String, Any> = emptyMap(),
    override val guardMissBehavior: GuardMissBehavior = GuardMissBehavior.SCORE
) : EvaluatorLeafConfig(), Scoreable {
    override val kind: EvaluatorLeafKind = EvaluatorLeafKind.Condition.Tree
}

/**
 * 兼容性解析函数：
 * Rule 类型通过 Guarded 接口获取显式守卫条件；
 * Condition 类型通过 sourceId + args 兜底构建 ConditionRef。
 */
fun EvaluatorLeafConfig.resolveConditionPayload(): ConditionPayload {
    val guard = (this as? Guarded)?.guardCondition
    if (guard != null) return guard
    return ConditionPayload.ConditionRef(conditionId = sourceId, args = args)
}

fun buildEvaluatorLeafConfig(
    nodeId: String,
    selectedLeaf: EvaluatorLeafMeta,
    existing: EvaluatorLeafConfig?,
    scoreEffect: ScoreEffect,
    extArgs: Map<String, Any>
): EvaluatorLeafConfig {

    val existingGuardMissBehavior = existing?.guardMissBehavior ?: GuardMissBehavior.SCORE

    val guardCondition = (existing as? Guarded)?.guardCondition
        ?: if (selectedLeaf.kind is EvaluatorLeafKind.Condition.Plain) {
            ConditionPayload.ConditionRef(conditionId = selectedLeaf.sourceId, args = extArgs)
        } else null

    return when (selectedLeaf.kind) {
        is EvaluatorLeafKind.Rule.Coded -> RuleLeafConfig(
            nodeId = nodeId,
            sourceId = selectedLeaf.sourceId,
            args = extArgs,
            guardCondition = (existing as? Guarded)?.guardCondition,
            scoreEffect = scoreEffect,
            guardMissBehavior = existingGuardMissBehavior
        )

        is EvaluatorLeafKind.Rule.Orthogonal -> OrthogonalRuleLeafConfig(
            nodeId = nodeId,
            sourceId = selectedLeaf.sourceId,
            guardCondition = guardCondition,
            scoreEffect = scoreEffect,
            args = extArgs,
            guardMissBehavior = existingGuardMissBehavior
        )

        is EvaluatorLeafKind.Condition.Orthogonal -> OrthogonalConditionLeafConfig(
            nodeId = nodeId,
            sourceId = selectedLeaf.sourceId,
            guardCondition = (existing as? OrthogonalConditionLeafConfig)?.guardCondition
                ?: (guardCondition as? ConditionPayload.PipelineRef)
                ?: error("正交条件必须配置 PipelineRef"),
            scoreEffect = scoreEffect,
            args = extArgs,
            guardMissBehavior = existingGuardMissBehavior
        )

        is EvaluatorLeafKind.Condition.Plain -> ConditionLeafConfig(
            nodeId = nodeId,
            sourceId = selectedLeaf.sourceId,
            scoreEffect = scoreEffect,
            args = extArgs,
            guardMissBehavior = existingGuardMissBehavior
        )

        is EvaluatorLeafKind.Condition.Tree -> ConditionTreeLeafConfig(
            nodeId = nodeId,
            sourceId = selectedLeaf.sourceId,
            scoreEffect = scoreEffect,
            args = extArgs,
            guardMissBehavior = existingGuardMissBehavior
        )
    }
}


