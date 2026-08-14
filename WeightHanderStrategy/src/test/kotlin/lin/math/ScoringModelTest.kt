package lin.math

import lin.bean.ComboCard
import lin.domain.context.*
import lin.weightHandler.GeneralMinionWeightHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class ScoringModelTest {

    @Test
    fun testScoringPrimitivesDualMode() {
        // 1. 验证双轨制轻量微调参数：CostWeight, PenaltyWeight 与 ComboDecayFactor
        assertEquals(0.5, CostWeight, 0.001)
        assertEquals(0.35, PenaltyWeight, 0.001)
        assertEquals(0.1, ComboCardWeight, 0.001)
        assertEquals(0.85, ComboDecayFactor, 0.001)

        // 验证 PenaltyWeight / CostWeight 比值在安全区间 [0.55, 0.70] (0.35 / 0.5 = 0.70)
        val ratio = PenaltyWeight / CostWeight
        assertTrue("PenaltyWeight/CostWeight ratio ($ratio) should be in [0.55, 0.70]", ratio in 0.55..0.70)

        // 2. 验证轻量基础分计算 (0.5 * cost，封顶 4.0)
        assertEquals(0.5, baseScore(1), 0.001)
        assertEquals(2.0, baseScore(4), 0.001)
        assertEquals(4.0, baseScore(8), 0.001)
        assertEquals(4.0, baseScore(9), 0.001) // 封顶 4.0

        // 3. 验证 5 费回合空 1 费的惩罚值 (PenaltyWeight=0.35)
        // Penalty = 0.35 * √1 * (1/5)^0.5 = 0.35 * 0.4472 = ~0.1565
        val p1 = remainingCostPenalty(1, 5)
        assertTrue("Penalty for remaining 1 cost out of 5 should be ~0.1565, got $p1", p1 in 0.15..0.16)

        // 4. 验证 5 费回合打 1 费卡 (base 0.5) 的净分差，消除负分偏见
        val netScore = baseScore(1) - p1
        assertTrue(
            "Net score for 1-cost card in 5 mana turn should be positive, got $netScore",
            netScore > 0.0
        )

        // 5. 验证非线性 comboPenalty = (n-1)^1.5 * 0.1
        assertEquals(0.0, comboPenalty(1), 0.001)
        assertEquals(0.1, comboPenalty(2), 0.001)
        assertEquals(2.0.pow(1.5) * 0.1, comboPenalty(3), 0.001)

        // 6. 验证组合衰减计算：第2张 1费卡基础分从 0.5 衰减为 0.5 * 0.85 = 0.425
        val card2DecayedBase = baseScore(1) * ComboDecayFactor
        assertEquals(0.425, card2DecayedBase, 0.001)
    }

    private fun setField(obj: Any, fieldName: String, value: Any?) {
        var cls: Class<*>? = obj.javaClass
        while (cls != null) {
            try {
                val f = cls.getDeclaredField(fieldName)
                f.isAccessible = true
                f.set(obj, value)
                return
            } catch (e: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
    }

    @Test
    fun testMinionStatFallbackAndAntiInversion() {
        val handler = GeneralMinionWeightHandler()

        // 辅助构造测试随从
        fun createMinion(
            id: String,
            name: String,
            cost: Int,
            atc: Int,
            health: Int,
            isTaunt: Boolean = false,
            isAura: Boolean = false
        ): ComboCard {
            val card = condition.createMockCard(
                cardId = id,
                cardType = club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum.MINION,
                cost = cost,
                atc = atc,
                health = health,
                isTaunt = isTaunt
            )
            setField(card, "entityName", name)
            if (isAura) {
                setField(card, "isAura", true)
            }
            return ComboCard(card = card, baseScore = baseScore(cost))
        }

        // 1. 验证白板随从身材梯度：2 费 2/3 (3.75) vs 7 费 7/7 (10.5) 差距拉开
        val card2Cost = createMinion("TEST_2", "2费随从", 2, 2, 3)
        val card4Cost = createMinion("TEST_4", "4费雪人", 4, 4, 5)
        val card7Cost = createMinion("TEST_7", "7费大哥", 7, 7, 7)

        val w2 = card2Cost.baseScore + handler.cardWeight(card2Cost)
        val w4 = card4Cost.baseScore + handler.cardWeight(card4Cost)
        val w7 = card7Cost.baseScore + handler.cardWeight(card7Cost)

        assertEquals(3.75, w2, 0.01) // 1.5 * 2.5 = 3.75
        assertEquals(6.75, w4, 0.01) // 1.5 * 4.5 = 6.75
        assertEquals(10.5, w7, 0.01) // 1.5 * 7.0 = 10.5

        assertTrue("7费大怪总分应显著高于2费小怪 (差值 > 6.0)", (w7 - w2) > 6.0)

        // 2. 验证减费随从防倒挂（光鳐 5/5 圣盾嘲讽）：9 费未减费时与 3 费减费时
        val rayAt9 = createMinion("REV_942_9", "光鳐9费", 9, 5, 5, isTaunt = true, isAura = true)
        val rayAt3 = createMinion("REV_942_3", "光鳐3费", 3, 5, 5, isTaunt = true, isAura = true)

        val totalRay9 = rayAt9.baseScore + handler.cardWeight(rayAt9)
        val totalRay3 = rayAt3.baseScore + handler.cardWeight(rayAt3)

        // 身材总战力 = 1.5 * 5.0 + 0.5(嘲讽) + 1.0(光环) = 9.0
        assertEquals(9.0, totalRay9, 0.01)
        assertEquals(9.0, totalRay3, 0.01)

        // 9费打出消耗9费拿到9分(性价比1.0)，3费打出消耗3费拿到9分(性价比3.0)，消除9费比3费分高的倒挂悖论
        assertTrue("光鳐9费与3费战力均锚定在身材9.0分，消除了亏模倒挂", totalRay9 <= totalRay3)
    }
}
