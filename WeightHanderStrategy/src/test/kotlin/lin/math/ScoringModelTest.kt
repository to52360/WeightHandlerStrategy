package lin.math

import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import lin.bean.CardCombinedConfig
import lin.bean.CardWeightInfo
import lin.domain.context.*
import lin.weightHandler.calcBaseValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class ScoringModelTest {

    @Test
    fun testScoringPrimitives() {
        assertEquals(0.5, CostWeight, 0.001)
        assertEquals(0.35, PenaltyWeight, 0.001)
        assertEquals(0.1, ComboCardWeight, 0.001)

        val ratio = PenaltyWeight / CostWeight
        assertTrue("PenaltyWeight/CostWeight ratio ($ratio) should be in [0.55, 0.70]", ratio in 0.55..0.70)

        // 剩余法力惩罚
        val p1 = remainingCostPenalty(1, 5)
        assertTrue("Penalty for remaining 1 cost out of 5 should be ~0.1565, got $p1", p1 in 0.15..0.16)

        // 非线性 comboPenalty = (n-1)^1.5 * 0.1
        assertEquals(0.0, comboPenalty(1), 0.001)
        assertEquals(0.1, comboPenalty(2), 0.001)
        assertEquals(2.0.pow(1.5) * 0.1, comboPenalty(3), 0.001)
    }

    @Test
    fun testCostValueConcave() {
        // 凹函数：1费=3.0，4费=6.0，9费=9.0
        assertEquals(3.0, costValue(1.0), 0.001)
        assertEquals(6.0, costValue(4.0), 0.001)
        assertEquals(9.0, costValue(9.0), 0.001)
        // 严格凹：2 * value(1) > value(2)（低费溢价、高费贬值）
        assertTrue("costValue should be strictly concave", costValue(1.0) * 2 > costValue(2.0))
    }

    @Test
    fun testCostValueCap() {
        // 等效费用超过 maxCost(=10) 时封顶，夸张身材（30/30 → 30费等效）不虚高
        assertEquals(costValue(10.0), costValue(30.0), 0.001)
        assertEquals(costValue(10.0), costValue(100.0), 0.001)
        // 封顶值 = 3 * √10 ≈ 9.487
        assertEquals(3.0 * 10.0.pow(0.5), costValue(10.0), 0.001)
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
    fun testBaseValueThreeWayBranch() {
        fun createMinion(
            id: String,
            cost: Int,
            atc: Int,
            health: Int,
            isTaunt: Boolean = false,
            isAura: Boolean = false
        ) = condition.createMockCard(
            cardId = id,
            cardType = CardTypeEnum.MINION,
            cost = cost,
            atc = atc,
            health = health,
            isTaunt = isTaunt
        ).also { card ->
            if (isAura) setField(card, "isAura", true)
        }

        // 1. 白板随从身材梯度（实时身材凹化）：2费2/3 → costValue(2.5)、7费7/7 → costValue(7)
        val v2 = calcBaseValue(createMinion("TEST_2", 2, 2, 3), null, 0)
        val v7 = calcBaseValue(createMinion("TEST_7", 7, 7, 7), null, 0)
        assertEquals(costValue(2.5), v2, 0.001)
        assertEquals(costValue(7.0), v7, 0.001)
        assertTrue("7费大怪身材分应高于2费小怪", v7 > v2)

        // 2. 减费随从防倒挂：光鳐 9费 vs 3费，身材分 = costValue(5) + 1.5(嘲讽+光环)，不依赖费用
        val ray9 = calcBaseValue(createMinion("REV_942_9", 9, 5, 5, isTaunt = true, isAura = true), null, 0)
        val ray3 = calcBaseValue(createMinion("REV_942_3", 3, 5, 5, isTaunt = true, isAura = true), null, 0)
        assertEquals(costValue(5.0) + 1.5, ray9, 0.001)
        assertEquals(ray9, ray3, 0.001)

        // 3. 法术兜底用「数据库初始费用」+ 保守系数：实时费用=0（减费后）、初始费用=4，
        //    兜底分 = SpellCostValueWeight * √4 = 1.5 * 2 = 3.0（比同费随从身材 costValue(4.5) 保守）
        val spell = condition.createMockCard(cardId = "TEST_SPELL", cardType = CardTypeEnum.SPELL, cost = 0)
        assertEquals(SpellCostValueWeight * 2.0, calcBaseValue(spell, null, 4), 0.001)
        assertTrue(
            "法术兜底应比同费随从身材更保守",
            calcBaseValue(spell, null, 4) < calcBaseValue(createMinion("T_4", 4, 4, 5), null, 0)
        )

        // 4. 配置费用优先：powerWeight=5 → costValue(5)，覆盖身材
        val cfgCard =
            condition.createMockCard(cardId = "CFG_5", cardType = CardTypeEnum.MINION, cost = 3, atc = 3, health = 4)
        val cfg = CardCombinedConfig(weightInfo = CardWeightInfo(cardId = "CFG_5", powerWeight = 5.0))
        assertEquals(costValue(5.0), calcBaseValue(cfgCard, cfg, 0), 0.001)
    }

    @Test
    fun testConfigCostReplacesStatNotAdditive() {
        fun createMinion(id: String, atc: Int, health: Int) =
            condition.createMockCard(cardId = id, cardType = CardTypeEnum.MINION, cost = 3, atc = atc, health = health)

        // 无配置：身材分 = costValue((atc+hp)/2)
        val noCfg = createMinion("CFG_BODY", 6, 6)
        val statOnly = calcBaseValue(noCfg, null, 0)
        assertEquals(costValue(6.0), statOnly, 0.001)

        // 配置 powerWeight=3：基础价值 = costValue(3)，完全取代身材分（二选一，不叠加）
        val withCfg = createMinion("CFG_BODY", 6, 6)
        val cfg = CardCombinedConfig(weightInfo = CardWeightInfo(cardId = "CFG_BODY", powerWeight = 3.0))
        val configured = calcBaseValue(withCfg, cfg, 0)

        assertEquals(costValue(3.0), configured, 0.001)
        // 关键断言：配置后不含身材项，且不等于「配置分 + 身材分」的叠加
        assertTrue(
            "配置费用应完全取代身材分（二选一），而非叠加",
            configured != costValue(3.0) + costValue(6.0)
        )
        assertTrue("配置费用较低时不应高于身材兜底（证明未叠加）", configured < statOnly)
    }
}
