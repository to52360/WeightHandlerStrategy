package lin.bean.usePlan

/**
 * 评估树评分通道（树级属性，一棵树一个通道，非卡级）。
 *
 * 缺省值由绑定目标的候选策略推导（[fromCandidatePolicy]）：`GROUP` →
 * `GroupUseOverride.candidatePolicy`、`PURPOSE_TAG` → `PurposeTagIntentRule.defaultCandidatePolicy`、
 * `CARD` → 直接 `GENERAL`；无独立 defaultChannel/channel 字段。
 * 一棵树绑多个 bindingId 时取其缺省唯一值，不一致则报配置冲突、要求树显式声明。
 * 候选门控读 `tacticalScore > 0` 判断战术命中；非评估树加分（legacy/光环/种族/技能/combo）全归 [GENERAL]。
 */
enum class ScoreChannel {
    /** 一般价值通道：身材/节奏等普通分 */
    GENERAL,

    /** 战术通道：针对/解场/战吼等战术分，TACTICS_DOMINANT 候选门控读取 */
    TACTICAL;

    companion object {
        /**
         * 从候选策略推导缺省评分通道（Q-008）：
         * `TACTICS_DOMINANT` → [TACTICAL]（战术主导，树分即战术分）；`NORMAL` / `SURPLUS_ONLY` / null → [GENERAL]。
         */
        fun fromCandidatePolicy(policy: CandidatePolicy?): ScoreChannel = when (policy) {
            CandidatePolicy.TACTICS_DOMINANT -> TACTICAL
            else -> GENERAL
        }
    }
}
