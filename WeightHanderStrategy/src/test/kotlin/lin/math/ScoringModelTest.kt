package lin.math

import lin.domain.context.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class ScoringModelTest {

    @Test
    fun testScoringPrimitivesV21() {
        // 1. 验证 CostWeight, PenaltyWeight 与 ComboDecayFactor
        assertEquals(5.0, CostWeight, 0.001)
        assertEquals(3.5, PenaltyWeight, 0.001)
        assertEquals(0.7, ComboCardWeight, 0.001)
        assertEquals(0.85, ComboDecayFactor, 0.001)

        // 验证 PenaltyWeight / CostWeight 比值在安全区间 [0.55, 0.70]
        val ratio = PenaltyWeight / CostWeight
        assertTrue("PenaltyWeight/CostWeight ratio ($ratio) should be in [0.55, 0.70]", ratio in 0.55..0.70)

        // 2. 验证基础分计算 (5 * √cost)
        assertEquals(5.0, baseScore(1), 0.001)
        assertEquals(10.0, baseScore(4), 0.001)
        assertEquals(15.0, baseScore(9), 0.001)

        // 3. 验证 5 费回合空 1 费的惩罚值 (使用 PenaltyWeight=3.5)
        // Penalty = 3.5 * √1 * (1/5)^0.5 = 3.5 * 0.4472 = ~1.565
        val p1 = remainingCostPenalty(1, 5)
        assertTrue("Penalty for remaining 1 cost out of 5 should be ~1.565, got $p1", p1 in 1.5..1.6)

        // 4. 验证 5 费回合打 1 费卡 (base 5.0) 的净分差，消除负分偏见
        val netScore = baseScore(1) - p1
        assertTrue(
            "Net score for 1-cost card in 5 mana turn should be positive (~3.435), got $netScore",
            netScore > 0.0
        )

        // 5. 验证 V2.1 非线性 comboPenalty = (n-1)^1.5 * 0.7
        assertEquals(0.0, comboPenalty(1), 0.001)
        assertEquals(0.7, comboPenalty(2), 0.001)
        assertEquals(2.0.pow(1.5) * 0.7, comboPenalty(3), 0.001) // ~1.98

        // 6. 验证组合衰减计算：第2张 1费卡基础分从 5.0 衰减为 5.0 * 0.85 = 4.25
        val card2DecayedBase = baseScore(1) * ComboDecayFactor
        assertEquals(4.25, card2DecayedBase, 0.001)
    }
}
