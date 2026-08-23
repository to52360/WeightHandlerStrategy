package lin.rule.handler

/**
 * 规则执行后产生的结果意图
 */
sealed class RuleResult {
    // 继续/评估完成（rule 工厂只能返回此类型，控制流职责已移至守卫侧 EvalOutcome）
    data class Continue(
        val score: Double,
        val modifyCard: ComboCardAction? = null
    ) : RuleResult()

    /**
     * 对一张卡牌执行所有条件树根节点后的累计结果。
     * 由顶层函数 [evaluateCardRoots] 返回，供编排函数使用。
     *
     * 纯值聚合：控制语义由 EvalOutcome 承担——
     * "不参与评分"由 missValue=0 表达，全局禁止经 EvalSignal.Banned 异常穿透，
     * 本类型不携带任何控制分支。
     *
     * D-007（2026-08-22）「树分皆战术信号」：全树分单值聚合，无通道分桶
     * （Q-008 的 general/tactical 双通道字段已随 T-018 清理，消费方读 [score]）。
     */
    data class Accumulate(
        val score: Double,
        val actions: List<ComboCardAction>
    )
}
