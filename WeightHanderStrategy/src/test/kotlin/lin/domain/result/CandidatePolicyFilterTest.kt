package lin.domain.result

import lin.bean.*
import lin.bean.usePlan.CandidatePolicy
import lin.bean.usePlan.UseIntent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-008：候选策略过滤测试。
 * 第一轮：NORMAL 全纳、TACTICS_DOMINANT 需战术命中、SURPLUS_ONLY 排除。
 * 第二轮（D-005 起）：TACTICS_DOMINANT / SURPLUS_ONLY / NORMAL 统一按 powerWeight > 0（正总分）。
 */
class CandidatePolicyFilterTest {

    private fun buildCard(
        policy: CandidatePolicy,
        tacticalScore: Double = 0.0,
        baseValue: Double = 5.0,
        extPowerWeight: Double = 0.0
    ): ComboCard {
        val info = CardWeightInfo(cardId = "TEST_${policy}_${tacticalScore}", powerWeight = 1.0)
        val card = ComboCard(
            combinedConfig = CardCombinedConfig(
                weightInfo = info,
                useIntent = UseIntent(candidatePolicy = policy)
            ),
            card = condition.createMockCard(cardId = "TEST_${policy}_${tacticalScore}"),
            baseValue = baseValue
        )
        card.extPowerWeight = extPowerWeight
        card.tacticalScore = tacticalScore
        return card
    }

    // ── 第一轮 ──

    @Test
    fun `NORMAL 无条件通过第一轮`() {
        assertTrue(buildCard(CandidatePolicy.NORMAL).passesFirstRoundCandidate())
        // 即使负总分（extPowerWeight 负）也进第一轮，由组合搜索器内部决定是否入选
        assertTrue(buildCard(CandidatePolicy.NORMAL, extPowerWeight = -10.0).passesFirstRoundCandidate())
    }

    @Test
    fun `TACTICS_DOMINANT 战术命中才通过第一轮`() {
        assertTrue(buildCard(CandidatePolicy.TACTICS_DOMINANT, tacticalScore = 3.0).passesFirstRoundCandidate())
        assertFalse(buildCard(CandidatePolicy.TACTICS_DOMINANT, tacticalScore = 0.0).passesFirstRoundCandidate())
        assertFalse(buildCard(CandidatePolicy.TACTICS_DOMINANT, tacticalScore = -2.0).passesFirstRoundCandidate())
    }

    @Test
    fun `SURPLUS_ONLY 第一轮始终排除`() {
        assertFalse(buildCard(CandidatePolicy.SURPLUS_ONLY).passesFirstRoundCandidate())
        assertFalse(buildCard(CandidatePolicy.SURPLUS_ONLY, extPowerWeight = 10.0).passesFirstRoundCandidate())
    }

    // ── 第二轮 ──

    @Test
    fun `第二轮 TACTICS_DOMINANT 按正总分判断（D-005 放宽）`() {
        // 有正底分但无战术命中：余费内可将就出白板（不再死捏）
        assertTrue(
            buildCard(
                CandidatePolicy.TACTICS_DOMINANT,
                tacticalScore = 0.0,
                baseValue = 5.0
            ).passesSecondRoundCandidate()
        )
        // 负总分（亏模）：仍不出
        assertFalse(
            buildCard(CandidatePolicy.TACTICS_DOMINANT, tacticalScore = 0.0, baseValue = 5.0, extPowerWeight = -10.0)
                .passesSecondRoundCandidate()
        )
    }

    @Test
    fun `第二轮 SURPLUS_ONLY 正总分才通过`() {
        assertTrue(buildCard(CandidatePolicy.SURPLUS_ONLY, extPowerWeight = 2.0).passesSecondRoundCandidate())
        // 负总分需压过默认底分（baseValue=5）才构成「负出」
        assertFalse(buildCard(CandidatePolicy.SURPLUS_ONLY, extPowerWeight = -10.0).passesSecondRoundCandidate())
    }

    @Test
    fun `第二轮 NORMAL 正总分才通过`() {
        assertTrue(buildCard(CandidatePolicy.NORMAL, extPowerWeight = 2.0).passesSecondRoundCandidate())
        assertFalse(buildCard(CandidatePolicy.NORMAL, extPowerWeight = -10.0).passesSecondRoundCandidate())
    }
}
