package lin.rule.handler

/**
 * 规则执行后产生的结果意图
 */
sealed class RuleResult {
    // 一票否决/剪枝
    //todo 评分加入控制,ui还有给于表达控制的字段
    object Prune : RuleResult()

    // 继续/评估完成
    data class Continue(
        val score: Double,
        val modifyCard: ComboCardAction? = null
    ) : RuleResult()

    /**
     * 对一张卡牌执行所有条件树根节点后的累计结果。
     * 由顶层函数 [evaluateCardRoots] 返回，供编排函数使用。
     */
    data class Accumulate(
        val score: Double,
        val actions: List<ComboCardAction>,
        val pruned: Boolean
    )
}
