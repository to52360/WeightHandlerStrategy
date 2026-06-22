package lin.rule.handler

/**
 * 评估树节点求值结果（三态模型）。
 *
 * 将原 [RuleResult.Prune] 承载的两个语义拆分：
 * - "逻辑不成立" → [Skipped]（守卫未命中，给兜底分）
 * - "控制流剪枝" → [Pruned]（终止整棵评估树）
 *
 * 守卫通过 → [Matched]（规则评分完成）。
 */
sealed class EvalOutcome {
    /** 守卫通过 + 规则评分完成 */
    data class Matched(
        val score: Double,
        val modifyCard: ComboCardAction? = null
    ) : EvalOutcome()

    /** 守卫未命中，使用 missValue 兜底，继续评估其他节点 */
    data class Skipped(val score: Double) : EvalOutcome()

    /** 控制流剪枝，终止整棵评估树 */
    object Pruned : EvalOutcome()
}
