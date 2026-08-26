package lin.domain.result

import condition.createMockCard
import lin.bean.*
import lin.bean.usePlan.UseIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-026：候选门控过滤（(N, ts) 二元组模型，替代三态 CandidatePolicy 枚举）。
 * 第一轮：tacticalScore != 0（战术命中，不论 N）或 N == 0 才进；ts == 0 且 N > 0 则惜售。
 * 第二轮：统一按 powerWeight > 0（正总分）判断（D-005）。
 * N 解析链：逐卡小数位 > 分组行为 > tag 默认 > 0。
 */
class CandidatePolicyFilterTest {

    private fun buildCard(
        n: Int? = null,
        cost: Int = 3,
        tacticalScore: Double = 0.0,
        baseValue: Double = 5.0,
        extPowerWeight: Double = 0.0
    ): ComboCard {
        val info = CardWeightInfo(cardId = "TEST_${n}_${tacticalScore}", powerWeight = 1.0, surplusIdleThreshold = n)
        val card = ComboCard(
            combinedConfig = CardCombinedConfig(
                weightInfo = info,
                useIntent = UseIntent()
            ),
            card = createMockCard(cardId = "TEST_${n}_${tacticalScore}", cost = cost),
            baseValue = baseValue
        )
        card.extPowerWeight = extPowerWeight
        card.tacticalScore = tacticalScore
        return card
    }

    // ── 第一轮 ──

    @Test
    fun `N 未配置无条件通过第一轮`() {
        // N == 0（无惜售诉求）：战术无关直接放行
        assertTrue(buildCard(n = null).passesFirstRoundCandidate())
        // 即使负总分（extPowerWeight 负）也进第一轮，由组合搜索器内部决定是否入选
        assertTrue(buildCard(n = null, extPowerWeight = -10.0).passesFirstRoundCandidate())
    }

    @Test
    fun `N 大于 0 无战术立场才惜售`() {
        // 命中 = ts≠0，不论 N：正负都过；仅 ts==0（无战术立场）且 N>0 才惜售排除
        assertTrue(buildCard(n = 1, tacticalScore = 3.0).passesFirstRoundCandidate())
        assertTrue(buildCard(n = 1, tacticalScore = -2.0).passesFirstRoundCandidate())
        assertFalse(buildCard(n = 1, tacticalScore = 0.0).passesFirstRoundCandidate())
    }

    // ── N 解析链（逐卡 > 分组 > tag 默认 > 0）──

    @Test
    fun `N 解析链 逐卡优先于分组与 tag`() {
        val card = ComboCard(
            combinedConfig = CardCombinedConfig(
                weightInfo = CardWeightInfo("c1", 1.0, surplusIdleThreshold = 2),
                groupSurplusIdleThreshold = 5,
                useIntent = UseIntent(tagDefaultSurplusIdleThreshold = 1)
            ),
            card = createMockCard(cardId = "c1"),
            baseValue = 5.0
        )
        assertEquals(2, card.surplusIdleThreshold())
        assertFalse(card.passesFirstRoundCandidate()) // N=2>0 且 ts=0 → 惜售
    }

    @Test
    fun `N 解析链 分组优先于 tag`() {
        val card = ComboCard(
            combinedConfig = CardCombinedConfig(
                weightInfo = CardWeightInfo("c2", 1.0),
                groupSurplusIdleThreshold = 5,
                useIntent = UseIntent(tagDefaultSurplusIdleThreshold = 1)
            ),
            card = createMockCard(cardId = "c2"),
            baseValue = 5.0
        )
        assertEquals(5, card.surplusIdleThreshold())
    }

    @Test
    fun `N 解析链 tag 默认兜底`() {
        val card = ComboCard(
            combinedConfig = CardCombinedConfig(
                weightInfo = CardWeightInfo("c3", 1.0),
                useIntent = UseIntent(tagDefaultSurplusIdleThreshold = 1)
            ),
            card = createMockCard(cardId = "c3"),
            baseValue = 5.0
        )
        assertEquals(1, card.surplusIdleThreshold())
        assertFalse(card.passesFirstRoundCandidate())
    }

    // ── 第二轮 ──

    @Test
    fun `第二轮 正总分才通过`() {
        // 有正底分但无战术命中：余费内可垫出白板（不再死捏）
        assertTrue(buildCard(n = null).passesSecondRoundCandidate())
        // 负总分（亏模）：仍不出
        assertFalse(buildCard(n = null, extPowerWeight = -10.0).passesSecondRoundCandidate())
    }

    // ── 余费门槛 ──

    @Test
    fun `passesSurplusGate 战术命中直接放行`() {
        assertTrue(buildCard(n = 3, tacticalScore = 1.0).passesSurplusGate(idleCost = 0))
    }

    @Test
    fun `passesSurplusGate 未命中需空闲达标`() {
        // N=2 的 3 费牌：空闲 ≥ 3+2=5 才垫（垫出后仍须剩 2 费）
        val card = buildCard(n = 2, cost = 3)
        assertFalse(card.passesSurplusGate(idleCost = 4))
        assertTrue(card.passesSurplusGate(idleCost = 5))
    }
}
