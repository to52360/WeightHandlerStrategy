package lin.rule.handler

/**
 * 规则执行后产生的结果意图
 */
sealed class RuleResult {
    // 一票否决/剪枝
    object Prune : RuleResult()

    // 继续/评估完成
    data class Continue(
        val feasible: Boolean,
        val score: Double,
        val modifyCard: ComboCardAction? = null
    ) : RuleResult()
}
