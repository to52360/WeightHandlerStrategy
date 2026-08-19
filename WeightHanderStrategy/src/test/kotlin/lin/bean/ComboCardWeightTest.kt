package lin.bean

import condition.createMockCard
import lin.domain.context.NotWeight
import lin.domain.context.UnUseWeight
import org.junit.Assert.*
import org.junit.Test

/**
 * T-009：addWeight 断开 useGroupOrder 污染 + isUnUse 移除 LastUseGroupId 副作用。
 */
class ComboCardWeightTest {

    private fun buildCard(): ComboCard {
        val info = CardWeightInfo(cardId = "TEST_WEIGHT", powerWeight = 1.0)
        return ComboCard(
            combinedConfig = CardCombinedConfig(weightInfo = info),
            card = createMockCard(cardId = "TEST_WEIGHT")
        )
    }

    @Test
    fun `addWeight 只改 extPowerWeight 不改 useGroupOrder`() {
        val card = buildCard()
        val orderBefore = card.useGroupOrder
        val extBefore = card.extPowerWeight
        card.addWeight(3.0)
        assertEquals(extBefore + 3.0, card.extPowerWeight, 1e-9)
        assertEquals(orderBefore, card.useGroupOrder, 1e-9) // 不再被污染
    }

    @Test
    fun `isUnUse 纯判断 unUse 标记`() {
        val card = buildCard()
        assertFalse(card.isUnUse())

        card.addWeight(-20.0) // 负分（软惩罚）≠ unUse
        assertFalse(card.isUnUse()) // 不因负分被判不可用
        assertEquals(25, card.useGroupId) // DefUseGroupId 未被改成 LastUseGroupId

        card.unUse()
        assertTrue(card.isUnUse())
    }

    @Test
    fun `unUse 后 powerWeight 为 UnUseWeight`() {
        val card = buildCard()
        card.unUse()
        assertEquals(UnUseWeight, card.extPowerWeight, 1e-9)
        assertTrue(card.isUnUse())
        assertTrue(card.powerWeight < NotWeight)
    }
}
