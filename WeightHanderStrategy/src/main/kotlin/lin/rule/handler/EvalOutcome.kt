package lin.rule.handler

/**
 * 评估树节点求值结果（三态模型）。
 *
 * - [Matched]：守卫通过 + 规则评分完成
 * - [Skipped]：守卫未命中，使用 missValue 兜底（missValue=0 即"不参与评分"），继续评估其他节点
 * - [Banned]：守卫未命中即命中禁止条件，全局穿透整棵评估树，强制当前卡牌不可打出
 *
 * 控制语义边界：
 * - "不参与评分"用数值方案表达（missValue=0），不设独立剪枝信号（PRUNE 已并入 SCORE）。
 * - [Banned] 是全局信号，需穿透整棵评估树到达编排层（副作用 unUse 在编排层处理），
 *   通过 [EvalSignal.Banned] 异常跨树传递。
 */
sealed class EvalOutcome {
    /** 守卫通过 + 规则评分完成 */
    data class Matched(
        val score: Double,
        val modifyCard: ComboCardAction? = null
    ) : EvalOutcome()

    /** 守卫未命中，使用 missValue 兜底，继续评估其他节点 */
    data class Skipped(val score: Double) : EvalOutcome()

    /**
     * 强制禁止：守卫未命中即命中禁止条件，全局穿透整棵评估树，强制当前卡牌不可打出。
     * 副作用（card.unUse()）统一由编排层处理。
     */
    object Banned : EvalOutcome()
}

// ARCH-UNSETTLED purpose-tag-extension/U-002: 用异常（EvalSignal）作为全局控制信号是隐式控制流——
// 调用方需知悉抛点（evaluateCardRoots）与捕获点（weightEvaluator 编排边界）。
// 仅 Banned 全局穿透一条路径使用。
// 待确认：是否换回显式 sealed 返回值传递（代价是 Accumulate 重带控制分支）。
internal sealed class EvalSignal(message: String) : Exception(message) {
    object Banned : EvalSignal("banned")
}
