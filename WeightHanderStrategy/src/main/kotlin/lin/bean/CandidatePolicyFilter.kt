package lin.bean

import lin.bean.usePlan.CandidatePolicy
import lin.domain.context.NotWeight

/**
 * 候选策略过滤（T-008，D-003）：
 *
 * 候选资格决定一张牌「能否进入哪一轮」，与 `UseStage`（排序）/`replanAfterUse`（执行生命周期）互不推导。
 * - 第一轮主组合：NORMAL 看总分、TACTICS_DOMINANT 需战术命中（tacticalScore > 0）、SURPLUS_ONLY 排除。
 * - 第二轮余费：SURPLUS_ONLY 需正总分、NORMAL 正总分、TACTICS_DOMINANT 仍只需战术命中（无战术不出，不当白板垫费）。
 *
 * 注意：候选门控只做「进不进候选」，不调用 `unUse()`/Banned——那是 EvalOutcome.Banned 硬禁语义（永久禁出），
 * 与本软门控（暂时不出）互不重叠。
 */
fun ComboCard.passesFirstRoundCandidate(): Boolean = when (candidatePolicy()) {
    CandidatePolicy.SURPLUS_ONLY -> false
    CandidatePolicy.TACTICS_DOMINANT -> tacticalScore > 0.0
    CandidatePolicy.NORMAL -> true
}

/** 第二轮余费候选资格：费用门槛由调用方（processLessCost）按 cost <= remainingCost 控制。 */
fun ComboCard.passesSecondRoundCandidate(): Boolean = when (candidatePolicy()) {
    CandidatePolicy.TACTICS_DOMINANT -> tacticalScore > 0.0
    CandidatePolicy.SURPLUS_ONLY, CandidatePolicy.NORMAL -> powerWeight > NotWeight
}

/** 候选策略（运行期 UseIntent 中推导好的值），缺省 NORMAL。 */
fun ComboCard.candidatePolicy(): CandidatePolicy =
    useIntent()?.candidatePolicy ?: CandidatePolicy.NORMAL
