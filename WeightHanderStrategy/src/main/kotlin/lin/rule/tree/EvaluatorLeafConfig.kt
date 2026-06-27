package lin.rule.tree

import lin.rule.condition.ConditionPayload
import lin.rule.score.ScoreEffect

// ── 标记接口 ──

sealed interface Scoreable {
    val scoreEffect: ScoreEffect
}

sealed interface Guarded {
    val guardCondition: ConditionPayload?
}

// ── 叶子配置 sealed 继承树 ──

/**
 * 评估树叶子配置的 sealed 继承树。
 * 通过中间层 [Condition] / [Rule] 实现 category 级编译期类型区分，
 * Condition 类叶子 scoreEffect 编译期收窄为 ConstantScore。
 *
 * 编译逻辑（compileGuardLogic/compileScoreLogic）不放在这里——
 * 叶子是被动数据，编译行为依赖外部 registry 服务，归 RuleTreeBinding 外部编排。
 */
sealed class EvaluatorLeafConfig {
    abstract val nodeId: String
    abstract val sourceId: String
    abstract val args: Map<String, Any>
    abstract val guardMissBehavior: GuardMissBehavior

    /** 条件类叶子：固定绑定 ConstantScore，条件本体由 sourceId 或 PipelineRef 提供 */
    sealed class Condition : EvaluatorLeafConfig(), Scoreable {
        abstract override val scoreEffect: ScoreEffect.ConstantScore
    }

    /** 规则类叶子：ScoreEffect 无限制，具备显式守卫条件 */
    sealed class Rule : EvaluatorLeafConfig(), Scoreable, Guarded {
        abstract override val guardCondition: ConditionPayload?
    }
}

// ── 5 个叶子配置子类（纯数据） ──

data class ConditionLeafConfig(
    override val nodeId: String,
    override val sourceId: String,
    override val scoreEffect: ScoreEffect.ConstantScore = ScoreEffect.ConstantScore(0.0),
    override val args: Map<String, Any> = emptyMap(),
    override val guardMissBehavior: GuardMissBehavior = GuardMissBehavior.SCORE
) : EvaluatorLeafConfig.Condition()

data class ConditionTreeLeafConfig(
    override val nodeId: String,
    override val sourceId: String,
    override val scoreEffect: ScoreEffect.ConstantScore = ScoreEffect.ConstantScore(0.0),
    override val args: Map<String, Any> = emptyMap(),
    override val guardMissBehavior: GuardMissBehavior = GuardMissBehavior.SCORE
) : EvaluatorLeafConfig.Condition()

data class OrthogonalConditionLeafConfig(
    override val nodeId: String,
    override val sourceId: String = "orthogonal_condition",
    override val guardCondition: ConditionPayload.PipelineRef,
    override val scoreEffect: ScoreEffect.ConstantScore = ScoreEffect.ConstantScore(0.0),
    override val args: Map<String, Any> = emptyMap(),
    override val guardMissBehavior: GuardMissBehavior = GuardMissBehavior.SCORE
) : EvaluatorLeafConfig.Condition(), Guarded

data class RuleLeafConfig(
    override val nodeId: String,
    override val sourceId: String,
    override val args: Map<String, Any> = emptyMap(),
    override val guardCondition: ConditionPayload? = null,
    override val scoreEffect: ScoreEffect = ScoreEffect.ConstantScore(0.0),
    override val guardMissBehavior: GuardMissBehavior = GuardMissBehavior.SCORE
) : EvaluatorLeafConfig.Rule()

data class OrthogonalRuleLeafConfig(
    override val nodeId: String,
    override val sourceId: String = "orthogonal_rule",
    override val guardCondition: ConditionPayload? = null,
    override val scoreEffect: ScoreEffect = ScoreEffect.SourceScore(sourceId = "", operatorId = "", missValue = 0.0),
    override val args: Map<String, Any> = emptyMap(),
    override val guardMissBehavior: GuardMissBehavior = GuardMissBehavior.SCORE
) : EvaluatorLeafConfig.Rule()

// ── 工具函数 ──

/**
 * Rule 类型通过 Guarded 接口获取显式守卫条件；
 * Condition 类型通过 sourceId + args 兜底构建 ConditionRef。
 */
fun EvaluatorLeafConfig.resolveConditionPayload(): ConditionPayload {
    val guard = (this as? Guarded)?.guardCondition
    if (guard != null) return guard
    return ConditionPayload.ConditionRef(conditionId = sourceId, args = args)
}
