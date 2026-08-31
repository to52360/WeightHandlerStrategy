package lin.domain.strategy

import condition.createMockCard
import lin.bean.CardCombinedConfig
import lin.bean.CardWeightInfo
import lin.bean.ComboCard
import lin.bean.usePlan.PurposeTagId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-004：额外费用识别迁 EXTRA_COST 标签通道测试（原 useGroupId == COINGroupId 旧通道）。
 *
 * 谓词只认标签——无论牌的 useGroupId 是什么，持有 EXTRA_COST 标签即路由进
 * ExtCostStrategy 双世界比较。
 */
class ExtCostPredicateTest {

    private fun cardWithTags(tags: Set<PurposeTagId> = emptySet()): ComboCard = ComboCard(
        card = createMockCard(cardId = "TEST_EXT_COST"),
        combinedConfig = CardCombinedConfig(
            weightInfo = CardWeightInfo(cardId = "TEST_EXT_COST", powerWeight = 1.0),
            purposeTags = tags
        )
    )

    @Test
    fun `持有EXTRA_COST标签的牌命中谓词`() {
        assertTrue(ExtCostStrategy.extCostPredicate(cardWithTags(setOf(PurposeTagId.EXTRA_COST))))
    }

    @Test
    fun `EXTRA_COST与其他标签并存仍命中`() {
        assertTrue(
            ExtCostStrategy.extCostPredicate(
                cardWithTags(setOf(PurposeTagId.VALUE, PurposeTagId.EXTRA_COST))
            )
        )
    }

    @Test
    fun `无EXTRA_COST标签的牌不命中`() {
        assertFalse(ExtCostStrategy.extCostPredicate(cardWithTags(setOf(PurposeTagId.VALUE))))
    }

    @Test
    fun `无任何标签不命中`() {
        assertFalse(ExtCostStrategy.extCostPredicate(cardWithTags()))
    }

    @Test
    fun `无combinedConfig不命中`() {
        val plain = ComboCard(card = createMockCard(cardId = "TEST_NO_CONFIG"))
        assertFalse(ExtCostStrategy.extCostPredicate(plain))
    }

    @Test
    fun `旧通道useGroupId不再参与识别`() {
        // 旧 useGroupId == COINGroupId 通道退役验证：即便误写硬币组 id，无标签也不命中
        val legacy = CardWeightInfo(cardId = "TEST_LEGACY", powerWeight = 1.0)
        legacy.useGroupId = 1
        val card = ComboCard(
            card = createMockCard(cardId = "TEST_LEGACY"),
            combinedConfig = CardCombinedConfig(weightInfo = legacy)
        )
        assertFalse(ExtCostStrategy.extCostPredicate(card))
    }
}
