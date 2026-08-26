package lin.domain.result

import condition.createMockCard
import lin.bean.CardCombinedConfig
import lin.bean.CardWeightInfo
import lin.bean.ComboCard
import lin.bean.usePlan.UseIntent
import org.junit.Assert.*
import org.junit.Test

/**
 * T-011/Q-009：同轮余费统筹填充。
 *
 * 验证两点：
 * 1. `lessAbleUseCards()` 快路下不再截断为空（余费候选源保持完整）。
 * 2. `findBestCombination()` 主牌选完后，在剩余费用内填充余费牌并合并进同一 bestCombination。
 *
 * T-026：SURPLUS_ONLY 枚举退役，「只进填充不进主组合」语义由 N>0 + 战术未命中（ts=0）表达——
 * 惜售牌被第一轮候选过滤挡在主组合外，但仍可经余费门槛（空闲 ≥ 牌费+N）进填充层。
 */
class SurplusCostFillTest {

    private fun buildCard(
        cardId: String,
        cost: Int,
        extPowerWeight: Double,
        idleThreshold: Int? = null,
        tacticalScore: Double = 0.0
    ): ComboCard {
        val info = CardWeightInfo(cardId = cardId, powerWeight = 1.0, surplusIdleThreshold = idleThreshold)
        val card = ComboCard(
            combinedConfig = CardCombinedConfig(
                weightInfo = info,
                useIntent = UseIntent()
            ),
            card = createMockCard(cardId = cardId, cost = cost)
        )
        card.extPowerWeight = extPowerWeight
        card.tacticalScore = tacticalScore
        return card
    }

    private fun resultWith(cards: List<ComboCard>, cost: Int): EndWeightResult {
        val result = EndWeightResult(cards, cost)
        cards.forEach { result.processWeightAfter(it) }
        return result
    }

    @Test
    fun `快路下惜售牌被合并进 bestCombination`() {
        val main = buildCard("MAIN", cost = 2, extPowerWeight = 3.0)
        val surplus = buildCard("SURPLUS", cost = 1, extPowerWeight = 2.0, idleThreshold = 2)
        val result = resultWith(listOf(main, surplus), cost = 5)

        // 手牌总费用 3 < 5 → 快路
        assertTrue(result.isLessCost())
        result.findBestCombination()

        // 主牌 + 惜售牌（N=2 被第一轮挡出，但空闲 3 ≥ 1+2 门槛达标）被合并进同一组合
        assertEquals(setOf(main, surplus), result.bestCombination.toSet())
    }

    @Test
    fun `lessAbleUseCards 快路下不再截断为空`() {
        val main = buildCard("MAIN", cost = 2, extPowerWeight = 3.0)
        val surplus = buildCard("SURPLUS", cost = 1, extPowerWeight = 2.0, idleThreshold = 2)
        val result = resultWith(listOf(main, surplus), cost = 5)

        // 修复前：isLessCost() 时直接返回 emptyList，丢失余费候选源（N>0 惜售牌被截断）。
        // 修复后：落选卡 = 可用卡 - 已选组合（findBestCombination 前 bestCombination 为空）。
        assertTrue(result.isLessCost())
        assertEquals(setOf(main, surplus), result.lessAbleUseCards().toSet())
    }

    @Test
    fun `非快路下惜售牌在剩余费用内被填充`() {
        val main1 = buildCard("M1", cost = 2, extPowerWeight = 5.0)
        val main2 = buildCard("M2", cost = 3, extPowerWeight = 4.0)
        val surplus = buildCard("S1", cost = 1, extPowerWeight = 2.0, idleThreshold = 1)
        val result = resultWith(listOf(main1, main2, surplus), cost = 4)

        // 主牌总费用 6 >= 4 → 非快路，走组合搜索
        assertFalse(result.isLessCost())
        result.findBestCombination()

        // 主牌搜索只选一张（2+3 ≤ 4 里选权重更高的 main1）；剩余 2 费 ≥ 1+1 门槛 → 填充 surplus
        assertTrue(result.bestCombination.contains(main1))
        assertTrue(result.bestCombination.contains(surplus))
        assertEquals(2, result.bestCombination.size)
    }
}
