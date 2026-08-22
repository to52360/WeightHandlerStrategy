package lin.bean

import lin.bean.cardExt.base.isMinion
import lin.bean.usePlan.CandidatePolicy
import lin.domain.context.NotWeight
import lin.domain.context.TacticalScoreScale
import kotlin.math.max

/**
 * 候选策略过滤（T-008，D-003）：
 *
 * 候选资格决定一张牌「能否进入哪一轮」，与 `UseStage`（排序）/`replanAfterUse`（执行生命周期）互不推导。
 * - 第一轮主组合：NORMAL 看总分、TACTICS_DOMINANT 需战术命中（tacticalScore > 0）、SURPLUS_ONLY 排除。
 * - 第二轮余费门控（D-007 后双通道，正交互补）：
 *   ① [passesSecondRoundCandidate]（powerWeight > 0）= **动态亏模兜底**——评估树/规则给负分（这局面打出去亏）
 *     的牌挡在余费外，兼 unUse 防死循环；
 *   ② [passesSurplusGate]（空闲 ≥ N）= **静态持有意愿**——捏到几费才放行垫牌（D-007 双费数模型）。
 *
 * 注意：候选门控只做「进不进候选」，不调用 `unUse()`/Banned——那是 EvalOutcome.Banned 硬禁语义（永久禁出），
 * 与本软门控（暂时不出）互不重叠。
 */
fun ComboCard.passesFirstRoundCandidate(): Boolean = when (candidatePolicy()) {
    CandidatePolicy.SURPLUS_ONLY -> false
    CandidatePolicy.TACTICS_DOMINANT -> tacticalScore > 0.0
    CandidatePolicy.NORMAL -> true
}

/** 第二轮余费候选资格：费用门槛由调用方（fillSurplusCost）按 cost <= remainingCost 控制。 */
fun ComboCard.passesSecondRoundCandidate(): Boolean = when (candidatePolicy()) {
    // 负分动态亏模兜底（树/规则负分 → 不出）+ unUse 防死循环；D-005 时期的「放宽」注释已作废——
    // 静态持有意愿职责已移交 D-007 余费门槛（passesSurplusGate），本检查只保留负分通道职责。
    CandidatePolicy.TACTICS_DOMINANT, CandidatePolicy.SURPLUS_ONLY, CandidatePolicy.NORMAL -> powerWeight > NotWeight
}

/**
 * 余费候选完整判定（T-011 fillSurplusCost / T-013 compensateFailedCards 共享的集中变化点）：
 * 费用门槛（cost <= remainingCost）+ 第二轮候选资格（[passesSecondRoundCandidate]）+ 余费门槛（D-007）+ 满场随从排除。
 * 战场满时不出随从，与执行层 UseFunction 硬拦截语义一致。
 */
fun ComboCard.passesSurplusCandidate(remainingCost: Int, isFull: Boolean): Boolean =
    cost() <= remainingCost && passesSecondRoundCandidate() && passesSurplusGate(remainingCost)
            && !(isFull && isMinion())

/** 候选策略（运行期 UseIntent 中推导好的值），缺省 NORMAL。 */
fun ComboCard.candidatePolicy(): CandidatePolicy =
    useIntent()?.candidatePolicy ?: CandidatePolicy.NORMAL

// ====================================================================
// D-007 双费数机会成本模型 v3（T-015）：等效费 E（D-014 原语义，卡牌固有等效费，不含战术溢价）
// + 空闲放行门槛 N（决策量，直接配）+ 战术溢价（树分×scale，费单位）。
// 门与价值解耦：N 管「捏到几费」，E+溢价管「垫进去值多少」；无 G 中间量（v2 的 G/fallback 派生已删）。
// ====================================================================

/**
 * 等效费 E（D-014 语义：卡牌固有等效费用，不含战术溢价）：配置等效费（解码后整数）> 随从实时身材 (atc+hp)/2
 * > 法术实时费。phase-1 近似：法术用实时费（D-014 baseValue 走数据库初始费保守线，缓存属 MyWarManage 不可达）。
 */
fun ComboCard.equivalentCostValue(): Double {
    val configured = cardWeightInfo?.powerWeight ?: 0.0
    if (configured > 0.0) return configured
    return if (card.isMinion()) (card.atc + card.health) / 2.0 else cost().toDouble()
}

/**
 * 余费门槛 N：空闲费 ≥ N 才允许垫牌放行。未配置 = 1（随时可垫，D-005「无战术不死捏」——
 * 不设「普遍亏模」默认档，持有意愿一律显式配置）。
 * 解析链（T-019）：逐卡小数位（[CardWeightInfo.surplusIdleThreshold]）> 分组行为（[CardCombinedConfig.groupSurplusIdleThreshold]）
 * > 默认 1。分组级解决「一类牌统一捏、不用逐卡设置」（如解牌组统一 N=4）。
 */
fun ComboCard.surplusIdleThreshold(): Int =
    cardWeightInfo?.surplusIdleThreshold
        ?: combinedConfig?.groupSurplusIdleThreshold
        ?: 1

/**
 * fillValue（填充交付费值）= 等效费 E + 战术溢价（tacticalScore×[TacticalScoreScale]）。
 * 零命中 → E；树分越高溢价越大（战术命中=现在更值得垫）。scale 为分→费换算（树量纲费化后可退役，Q-024）。
 */
fun ComboCard.surplusFillValue(): Double =
    equivalentCostValue() + max(tacticalScore, 0.0) * TacticalScoreScale

/**
 * 余费门槛（D-007）：战术已命中（tacticalScore > 0）= 现在就是战术价值，直接放行；
 * 否则空闲费 ≥ N 才放行（N=4 即「3捏4放行」；「不贪心」= 直接配更小的 N）。
 */
fun ComboCard.passesSurplusGate(idleCost: Int): Boolean =
    tacticalScore > 0.0 || idleCost >= surplusIdleThreshold()
