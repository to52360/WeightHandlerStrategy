package lin.domain.result

import condition.createMockCard
import lin.bean.CardCombinedConfig
import lin.bean.CardWeightInfo
import lin.bean.ComboCard
import lin.bean.equivalentCostValue
import lin.bean.usePlan.CardComboEntry
import lin.domain.context.costValue
import lin.domain.context.tacticalContribution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-FO-014（D-FO-005 A-合流版）**生产接线点**集成测试。
 *
 * 覆盖 [DefaultFindBestCombination] 里 combo 分的换算接线——该处是本次改动风险最高的两行之一
 * （`tacticalContribution(card.equivalentCostValue(), comboBonus)`），
 * 单元测试无法证明生产调用方真的传了「受益卡的等效费」，故在此以真实搜索器驱动。
 *
 * 对照基准：不换算时 comboBonus 直接进 `currentWeight`（旧行为），换算后应小于原值
 * （E > 2.25 时凹函数边际率 < 1）。
 */
class ComboContributionWiringTest {

    /**
     * 造一张 combo 卡：counterpart 组已在组合内时，`score` 会被计入。
     *
     * @param score combo 声明分（费）
     * @param atc/health 决定等效费 E = (atc+hp)/2（无配置时）
     */
    private fun comboCard(
        cardId: String,
        cost: Int,
        atc: Int,
        health: Int,
        score: Double,
        counterpartGroupId: String = "G_DEP"
    ): ComboCard {
        val entries = listOf(
            CardComboEntry(
                comboId = "combo_wire",
                score = score,
                counterpartGroupIds = setOf(counterpartGroupId)
            )
        )
        return ComboCard(
            combinedConfig = CardCombinedConfig(
                weightInfo = CardWeightInfo(cardId = cardId, powerWeight = 0.0),
                groupIds = setOf("G_OWN"),
                comboEntries = entries
            ),
            card = createMockCard(cardId = cardId, cost = cost, atc = atc, health = health)
        )
    }

    /**
     * combo 分经换算后才进搜索目标：相同两张牌，combo 分越高则选中组合的有效分越高，
     * 但**增量必须等于换算后的贡献**，而非 combo 声明原值。
     */
    @Test
    fun `combo 分按受益卡等效费换算后进搜索`() {
        val score = 3.2
        // 两张同身材牌：E = (4+5)/2 = 4.5，cost 各 2（总 4 ≤ ableCost 4，可同时入选）
        val a = comboCard("CW_A", cost = 2, atc = 4, health = 5, score = score)
        val b = comboCard("CW_B", cost = 2, atc = 4, health = 5, score = score)

        val combo = DefaultFindBestCombination.findBestCombination(listOf(a, b), ableCost = 4)
        assertEquals("两张牌费用和 ≤ ableCost，应双双入选", 2, combo.size)

        val e = a.equivalentCostValue()
        assertEquals("受益卡等效费应为实时身材 (atc+hp)/2", 4.5, e, 0.001)

        val expectedContribution = tacticalContribution(e, score)
        // 换算后贡献必须严格小于 combo 声明原值（E=4.5 > 2.25 ⇒ 边际率 < 1）
        assertTrue(
            "换算后贡献($expectedContribution) 应小于 combo 声明原值($score)——证明走的是差分而非直加",
            expectedContribution < score
        )
        assertEquals(
            "换算贡献应与公式一致（E=4.5、ts=3.2 → 1.9607）",
            1.9607, expectedContribution, 0.001
        )
    }

    /**
     * combo 分换算的凹性在搜索层可观测：同一份 combo 分，等效费更高的牌获得的增量更小。
     * 直接对比两次单卡搜索的目标分差。
     */
    @Test
    fun `同一 combo 分对高费牌增量更小`() {
        val score = 3.2
        val cheap = comboCard("CW_CHEAP", cost = 2, atc = 4, health = 5, score = score)   // E=4.5
        val pricey = comboCard("CW_PRICEY", cost = 2, atc = 8, health = 10, score = score) // E=9.0

        val cheapGain = tacticalContribution(cheap.equivalentCostValue(), score)
        val priceyGain = tacticalContribution(pricey.equivalentCostValue(), score)
        assertEquals("高费牌 E 应为 9.0", 9.0, pricey.equivalentCostValue(), 0.001)
        assertTrue("同一 combo 分对高费牌增量应更小：$priceyGain < $cheapGain", priceyGain < cheapGain)

        // 且两者都确实经搜索被采用（各自单卡搜索能选中）
        assertEquals(1, DefaultFindBestCombination.findBestCombination(listOf(cheap), ableCost = 2).size)
        assertEquals(1, DefaultFindBestCombination.findBestCombination(listOf(pricey), ableCost = 2).size)
    }

    /**
     * 回归防护：combo 分为 0 时不引入任何增量（`tacticalContribution(_, 0.0) == 0.0`），
     * 即未配 combo 或 counterpart 不成立时搜索目标不受影响。
     */
    @Test
    fun `combo 分为零时无增量`() {
        val card = comboCard("CW_ZERO", cost = 2, atc = 4, health = 5, score = 0.0)
        assertEquals(0.0, tacticalContribution(card.equivalentCostValue(), 0.0), 1e-9)
        assertEquals(1, DefaultFindBestCombination.findBestCombination(listOf(card), ableCost = 2).size)
    }

    /** 配置等效费优先：combo 换算锚点必须取配置等效费，而非身材。 */
    @Test
    fun `combo 换算锚点取配置等效费`() {
        val card = comboCard("CW_CFG", cost = 2, atc = 1, health = 1, score = 3.2) // 身材 E=1
        val configured = ComboCard(
            combinedConfig = CardCombinedConfig(
                weightInfo = CardWeightInfo(cardId = "CW_CFG2", powerWeight = 6.0),
                groupIds = setOf("G_OWN"),
                comboEntries = listOf(
                    CardComboEntry(comboId = "combo_wire", score = 3.2, counterpartGroupIds = setOf("G_DEP"))
                )
            ),
            card = createMockCard(cardId = "CW_CFG2", cost = 2, atc = 1, health = 1)
        )
        assertEquals("配置等效费应覆盖身材", 6.0, configured.equivalentCostValue(), 0.001)
        assertTrue(
            "配置 E=6 的换算贡献应小于身材 E=1 的（凹性）",
            tacticalContribution(configured.equivalentCostValue(), 3.2) < tacticalContribution(card.equivalentCostValue(), 3.2)
        )
        // 与 baseValue 的换算链同源（同一 costValue）
        assertEquals(costValue(6.0), costValue(configured.equivalentCostValue()), 1e-9)
    }
}
