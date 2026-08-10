package lin.rule.tree

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
 * - [SCORE]：给 missValue 兜底分，继续评估其他节点（默认行为，向后兼容；
 *   missValue=0 即"该叶子不参与评分"）
 * - [PRUNE]：**门控短路**（2026-08-09 恢复）。守卫未命中 → [EvalOutcome.Pruned]，向父节点传播——
 *   AND 遇 Pruned 立即短路整棵 AND 子树（后续子节点不评估）、OR 同样短路、NOT 透传。
 *   典型场景：AND 内"多条件并列门控"（条件1 且 条件2 且 条件3 同时满足才加分），
 *   任一条件不满足则整棵不加分。Branch 只适合单条件分叉，多条件并列门控嵌套会爆炸，故需 PRUNE。
 * - [BAN]：强制禁止当前卡牌打出（守卫未命中即命中"禁止条件"，
 *   全局穿透整棵评估树并调用 [lin.bean.ComboCard.unUse]，副作用在编排层处理）
 *
 * @verify prune-semantics/K-001（2026-08-09 修订）：PRUNE 曾于 2026-08-03 并入 SCORE（当时判断
 *   "单叶子行为等价"），实战（libram_tutors 多条件门控）证明 Branch 无法优雅替代 2+ 条件并列门控，
 *   用户拍板恢复。等价性说明：单叶子层面 PRUNE=SCORE+missValue=0（都是该叶子 0 分），
 *   但 AND 组合层面不等价——PRUNE 短路整棵 AND，SCORE 不短路。
 *
 * @verify prune-semantics/Q-003（2026-08-09 标记待评估，不深入）：后续架构认知更新为
 *   "守卫+评估规则一体在叶子内"（ORTHOGONAL_RULE 的 guardCondition + SourceScore、或
 *   CONDITION_TREE 叶子条件树守卫 + ConstantScore）。多条件守卫天然由条件树组合，树级 AND 主要用于
 *   组合多个评分规则而非做门控。在此认知下 PRUNE 的实际使用场景收窄（单叶子守卫失败可用
 *   SCORE+missValue=0 等价表达），是否仍需要独立 PRUNE 枚举值待后续真实案例验证后再定。
 */
enum class GuardMissBehavior { SCORE, PRUNE, BAN }

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

data class EvaluatorLeafMeta(
    val kind: EvaluatorLeafKind,
    val sourceId: String,
    val name: String,
    val desc: String = "",
    val builtInFields: List<FieldSpec> = emptyList(),
    val fields: List<FieldSpec>,
    /** 新建叶子时的默认参数（条件树引用场景：预填树内参数作参考，D-007 语义 B）。 */
    val defaultArgs: Map<String, Any> = emptyMap()
)


