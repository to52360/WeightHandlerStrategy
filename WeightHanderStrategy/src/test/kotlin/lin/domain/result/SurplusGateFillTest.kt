package lin.domain.result

import condition.createMockCard
import lin.bean.*
import lin.bean.usePlan.CandidatePolicy
import lin.bean.usePlan.UseIntent
import org.junit.Assert.*
import org.junit.Test

/**
 * D-007 双费数机会成本模型 v3（T-015）：等效费 E（D-014 原语义，不含战术溢价）+ 空闲放行门槛 N（直接配）
 * → 将就门（空闲 ≥ N / 战术命中绕门）+ fillValue = E + 树分×scale（费单位，无 G/fallback 中间量）。
 *
 * 语义锚点（用户拍板例）：① 门槛 N=4 即「3捏4放行」；②「不贪心」= 本来 4 费放行的卡直接配 N=2；
 * ③ 未配置 = 随时可垫（无「普遍亏模」默认档）；④ 等效费用是固有语义（6 费牌等效 5 = 略亏模），不含战术溢价。
 */
class SurplusGateFillTest {

    private fun buildCard(
        cardId: String,
        policy: CandidatePolicy,
        cost: Int,
        powerWeight: Double,
        idleThreshold: Int? = null,
        tacticalScore: Double = 0.0
    ): ComboCard {
        val info = CardWeightInfo(
            cardId = cardId,
            powerWeight = powerWeight,
            surplusIdleThreshold = idleThreshold
        )
        val card = ComboCard(
            combinedConfig = CardCombinedConfig(
                weightInfo = info,
                useIntent = UseIntent(candidatePolicy = policy)
            ),
            card = createMockCard(cardId = cardId, cost = cost)
        )
        card.extPowerWeight = 1.0
        card.tacticalScore = tacticalScore
        return card
    }

    // ===== 将就门 =====

    @Test
    fun `配置档 N=4 即 3捏4放行`() {
        // 等效 5 费、门槛 4：空闲 3 费捏（3 < 4），空闲 4 费放行
        val card = buildCard("T1", CandidatePolicy.TACTICS_DOMINANT, cost = 2, powerWeight = 5.0, idleThreshold = 4)
        assertFalse(card.passesSurplusCandidate(remainingCost = 3, isFull = false))
        assertTrue(card.passesSurplusCandidate(remainingCost = 4, isFull = false))
    }

    @Test
    fun `不贪心 本来4费放行直接配N=2 空闲2费就考虑`() {
        val card = buildCard("T2", CandidatePolicy.TACTICS_DOMINANT, cost = 2, powerWeight = 5.0, idleThreshold = 2)
        assertFalse(card.passesSurplusCandidate(remainingCost = 1, isFull = false))
        assertTrue(card.passesSurplusCandidate(remainingCost = 2, isFull = false))
    }

    @Test
    fun `未配置门槛随时可垫 无普遍默认档`() {
        // 不配门槛 = N=1：D-005「无战术不死捏」，持有意愿一律显式配置
        val card = buildCard("T3", CandidatePolicy.TACTICS_DOMINANT, cost = 1, powerWeight = 3.0)
        assertTrue(card.passesSurplusCandidate(remainingCost = 1, isFull = false))
    }

    @Test
    fun `白板 NORMAL 未配置恒可填`() {
        val card = buildCard("N1", CandidatePolicy.NORMAL, cost = 2, powerWeight = 2.0)
        assertTrue(card.passesSurplusCandidate(remainingCost = 2, isFull = false))
    }

    @Test
    fun `大门槛软死捏 等效费再高也捏到门槛`() {
        // 等效 1 费、门槛 9：空闲 < 9 一律捏（硬死捏走 Banned）
        val card = buildCard("D", CandidatePolicy.TACTICS_DOMINANT, cost = 1, powerWeight = 1.0, idleThreshold = 9)
        assertFalse(card.passesSurplusCandidate(remainingCost = 5, isFull = false))
        assertTrue(card.passesSurplusCandidate(remainingCost = 9, isFull = false))
    }

    @Test
    fun `战术命中绕门`() {
        val card = buildCard(
            "T4", CandidatePolicy.TACTICS_DOMINANT, cost = 2,
            powerWeight = 5.0, idleThreshold = 4, tacticalScore = 8.0
        )
        // 门槛 4 但战术已命中：空闲 2 费（2 < 4 本该捏）也放行——现在就是战术价值
        assertTrue(card.passesSurplusCandidate(remainingCost = 2, isFull = false))
    }

    // ===== fillValue = 等效费 + 树分×scale（满足一些赚一点）=====

    @Test
    fun `fillValue 零命中等效费_树分线性加溢价`() {
        val base = { ts: Double ->
            buildCard(
                "F", CandidatePolicy.TACTICS_DOMINANT, cost = 2,
                powerWeight = 5.0, idleThreshold = 4, tacticalScore = ts
            )
        }
        // scale=0.4：零命中 → E=5.0；ts=5 → 5+2.0=7.0；ts=10 → 5+4.0=9.0
        assertEquals(5.0, base(0.0).surplusFillValue(), 1e-9)
        assertEquals(7.0, base(5.0).surplusFillValue(), 1e-9)
        assertEquals(9.0, base(10.0).surplusFillValue(), 1e-9)
    }

    // ===== 填充目标 max Σ fillValue =====

    @Test
    fun `同槽位高等效费白板胜亏模将就牌`() {
        val vanilla = buildCard("V", CandidatePolicy.NORMAL, cost = 2, powerWeight = 2.0)   // E=2
        val tactical = buildCard(
            "T5", CandidatePolicy.TACTICS_DOMINANT, cost = 2,
            powerWeight = 1.0, idleThreshold = 2
        )                                          // E=1（亏模）门槛2可过
        val result = SurplusFillCombination.findBestCombination(listOf(vanilla, tactical), ableCost = 2)
        assertEquals(listOf(vanilla), result)
    }

    @Test
    fun `白板填不满时将就牌补空隙`() {
        val tactical = buildCard(
            "T6", CandidatePolicy.TACTICS_DOMINANT, cost = 2,
            powerWeight = 1.0, idleThreshold = 2
        ) // E=1
        val result = SurplusFillCombination.findBestCombination(listOf(tactical), ableCost = 2)
        assertEquals(listOf(tactical), result)
    }

    @Test
    fun `组合填充保留 1加2填满3费`() {
        val one = buildCard("C1", CandidatePolicy.NORMAL, cost = 1, powerWeight = 1.0)   // fillValue 1
        val two = buildCard("C2", CandidatePolicy.NORMAL, cost = 2, powerWeight = 2.0)   // fillValue 2
        val three = buildCard("C3", CandidatePolicy.NORMAL, cost = 3, powerWeight = 2.0) // fillValue 2
        val result = SurplusFillCombination.findBestCombination(listOf(one, two, three), ableCost = 3)
        assertEquals(setOf(one, two), result.toSet())
    }

    // ===== 端到端：fillSurplusCost 走新目标 =====

    // ===== 端到端：fillSurplusCost 走新目标 =====

    @Test
    fun `主牌选完后将就门与费数目标端到端生效`() {
        val main = buildCard("M", CandidatePolicy.NORMAL, cost = 3, powerWeight = 3.0)
        main.extPowerWeight = 5.0
        val tactical = buildCard(
            "T7", CandidatePolicy.TACTICS_DOMINANT, cost = 2,
            powerWeight = 5.0, idleThreshold = 4
        ) // 门槛 4：剩余 2 费 < 4 → 捏
        val result = EndWeightResult(listOf(main, tactical), cost = 5)
        result.processWeightAfter(main)
        result.processWeightAfter(tactical)
        result.findBestCombination()

        // 主牌 3 费（第一轮 TACTICS_DOMINANT 无战术被排除），剩 2 费：门槛 4 未到，捏住不将就
        assertEquals(listOf(main), result.bestCombination)
    }

    // ===== T-020：晚到候选（技能）填充竞争 =====

    @Test
    fun `高配技能凭 fillValue 抢垫牌费用`() {
        val main = buildCard("M", CandidatePolicy.NORMAL, cost = 3, powerWeight = 3.0)
        main.extPowerWeight = 5.0
        val pad = buildCard("P", CandidatePolicy.SURPLUS_ONLY, cost = 2, powerWeight = 1.0) // 垫牌 fillValue 1
        val skill = buildCard("SK", CandidatePolicy.NORMAL, cost = 2, powerWeight = 5.0)    // 高配技能 fillValue 5
        val result = EndWeightResult(listOf(main, pad), cost = 5)
        result.processWeightAfter(main)
        result.processWeightAfter(pad)
        result.findBestCombination()
        // 主组合 3 费，垫牌占 2 费空闲
        assertEquals(setOf(main, pad), result.bestCombination.toSet())

        result.retryFillWithLateCandidate(skill, isFull = false)
        // 技能与垫牌同池竞争：fillValue 5 > 1 → 抢走垫牌的费用
        assertEquals(setOf(main, skill), result.bestCombination.toSet())
    }

    @Test
    fun `低配技能不抢垫牌`() {
        val main = buildCard("M2", CandidatePolicy.NORMAL, cost = 3, powerWeight = 3.0)
        val pad = buildCard("P2", CandidatePolicy.SURPLUS_ONLY, cost = 2, powerWeight = 2.0) // 垫牌 fillValue 2
        val skill = buildCard("SK2", CandidatePolicy.NORMAL, cost = 2, powerWeight = 1.0)    // 低配技能 fillValue 1
        val result = EndWeightResult(listOf(main, pad), cost = 5)
        result.processWeightAfter(main)
        result.processWeightAfter(pad)
        result.findBestCombination()

        result.retryFillWithLateCandidate(skill, isFull = false)
        assertEquals(setOf(main, pad), result.bestCombination.toSet())
    }

    @Test
    fun `预算耗尽技能不追加`() {
        val main = buildCard("M3", CandidatePolicy.NORMAL, cost = 5, powerWeight = 5.0)
        val skill = buildCard("SK3", CandidatePolicy.NORMAL, cost = 2, powerWeight = 5.0)
        val result = EndWeightResult(listOf(main), cost = 5)
        result.processWeightAfter(main)
        result.findBestCombination()

        result.retryFillWithLateCandidate(skill, isFull = false)
        assertEquals(listOf(main), result.bestCombination)
    }

    @Test
    fun `技能门槛未到不参与竞争`() {
        val main = buildCard("M4", CandidatePolicy.NORMAL, cost = 3, powerWeight = 3.0)
        val pad = buildCard("P4", CandidatePolicy.SURPLUS_ONLY, cost = 2, powerWeight = 1.0)
        val skill = buildCard("SK4", CandidatePolicy.NORMAL, cost = 2, powerWeight = 5.0, idleThreshold = 4) // 空闲2 < 4
        val result = EndWeightResult(listOf(main, pad), cost = 5)
        result.processWeightAfter(main)
        result.processWeightAfter(pad)
        result.findBestCombination()

        result.retryFillWithLateCandidate(skill, isFull = false)
        assertEquals(setOf(main, pad), result.bestCombination.toSet())
    }

    @Test
    fun `负分技能被余费门控拦截`() {
        val main = buildCard("M5", CandidatePolicy.NORMAL, cost = 3, powerWeight = 3.0)
        val pad = buildCard("P5", CandidatePolicy.SURPLUS_ONLY, cost = 2, powerWeight = 1.0)
        val skill = buildCard("SK5", CandidatePolicy.NORMAL, cost = 2, powerWeight = 5.0)
        skill.extPowerWeight = -10.0 // 负分动态亏模（树/规则给负）→ powerWeight < 0
        val result = EndWeightResult(listOf(main, pad), cost = 5)
        result.processWeightAfter(main)
        result.processWeightAfter(pad)
        result.findBestCombination()

        result.retryFillWithLateCandidate(skill, isFull = false)
        assertEquals(setOf(main, pad), result.bestCombination.toSet())
    }

    @Test
    fun `无垫牌时技能兜底占用空闲`() {
        val main = buildCard("M6", CandidatePolicy.NORMAL, cost = 3, powerWeight = 3.0)
        val skill = buildCard("SK6", CandidatePolicy.NORMAL, cost = 2, powerWeight = 1.0) // 低配也能占空闲
        val result = EndWeightResult(listOf(main), cost = 5)
        result.processWeightAfter(main)
        result.findBestCombination()

        result.retryFillWithLateCandidate(skill, isFull = false)
        assertEquals(setOf(main, skill), result.bestCombination.toSet())
    }
}
