package lin.tree_config.bridge

import lin.rule.condition.ConditionPayload
import lin.rule.score.ScoreEffect
import lin.rule.tree.*

// ── leafKind 推导（元数据键查找用） ──

val EvaluatorLeafConfig.leafKind: EvaluatorLeafKind
    get() = when (this) {
        is ConditionLeafConfig -> EvaluatorLeafKind.Condition.Plain
        is ConditionTreeLeafConfig -> EvaluatorLeafKind.Condition.Tree
        is OrthogonalConditionLeafConfig -> EvaluatorLeafKind.Condition.Orthogonal
        is RuleLeafConfig -> EvaluatorLeafKind.Rule.Coded
        is OrthogonalRuleLeafConfig -> EvaluatorLeafKind.Rule.Orthogonal
    }

// ── UI 侧 Config 工具 ──

fun EvaluatorLeafConfig.withGuardMissBehavior(behavior: GuardMissBehavior): EvaluatorLeafConfig = when (this) {
    is ConditionLeafConfig -> copy(guardMissBehavior = behavior)
    is ConditionTreeLeafConfig -> copy(guardMissBehavior = behavior)
    is OrthogonalConditionLeafConfig -> copy(guardMissBehavior = behavior)
    is RuleLeafConfig -> copy(guardMissBehavior = behavior)
    is OrthogonalRuleLeafConfig -> copy(guardMissBehavior = behavior)
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
            scoreEffect = scoreEffect as? ScoreEffect.ConstantScore ?: ScoreEffect.ConstantScore(0.0),
            args = extArgs,
            guardMissBehavior = existingGuardMissBehavior
        )

        is EvaluatorLeafKind.Condition.Plain -> ConditionLeafConfig(
            nodeId = nodeId,
            sourceId = selectedLeaf.sourceId,
            scoreEffect = scoreEffect as? ScoreEffect.ConstantScore ?: ScoreEffect.ConstantScore(0.0),
            args = extArgs,
            guardMissBehavior = existingGuardMissBehavior
        )

        is EvaluatorLeafKind.Condition.Tree -> ConditionTreeLeafConfig(
            nodeId = nodeId,
            sourceId = selectedLeaf.sourceId,
            scoreEffect = scoreEffect as? ScoreEffect.ConstantScore ?: ScoreEffect.ConstantScore(0.0),
            args = extArgs,
            guardMissBehavior = existingGuardMissBehavior
        )
    }
}
