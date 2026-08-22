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
     * Q-008：按评分通道分离——[generalScore]（一般价值）与 [tacticalScore]（战术价值）。
     * [score] = 两者之和。
     * D-007（2026-08-22）回归「树分皆战术信号」：战术信号消费方（门控/绕门/fillValue 溢价）已改读 [score]（全树分），
     * 通道分离无独立消费者，遗留待清理（TRACKER T-018）。
     */
    data class Accumulate(
        val generalScore: Double,
        val tacticalScore: Double,
        val actions: List<ComboCardAction>
    ) : RuleResult() {
        /** 旧语义总分数 = general + tactical（兼容既有消费方） */
        val score: Double
            get() = generalScore + tacticalScore
    }
}
