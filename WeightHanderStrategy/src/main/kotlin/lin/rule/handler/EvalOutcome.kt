package lin.rule.handler

/**
 * 评估树节点求值结果（四态模型）。
 *
 * - [Matched]：守卫通过 + 规则评分完成
 * - [Skipped]：守卫未命中，使用 missValue 兜底（missValue=0 即"不参与评分"），继续评估其他节点
 * - [Pruned]：守卫未命中且 guardMissBehavior=PRUNE，**门控短路**——向父节点传播，
 *   AND/OR 遇 Pruned 立即短路整棵子树（后续子节点不评估），NOT 透传
 * - [Banned]：守卫未命中即命中禁止条件，全局穿透整棵评估树，强制当前卡牌不可打出
 *
 * 控制语义边界：
 * - "不参与评分"用数值方案表达（missValue=0，SCORE）；"门控短路"由 [Pruned]（PRUNE）表达，
 *   两者在单叶子层面等价（都是该叶子 0 分），但 AND 组合层面不等价——PRUNE 短路整棵 AND，
 *   SCORE 只是该叶子 0 分、其他子节点照常累加（2026-08-09 恢复，见 prune-semantics/D-001）。
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

    /** 门控短路：守卫未命中且 guardMissBehavior=PRUNE，短路父节点（AND/OR）子树 */
    object Pruned : EvalOutcome()

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
