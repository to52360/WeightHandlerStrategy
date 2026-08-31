package lin.utils.startup

import club.xiaojiawei.hsscriptcardsdk.data.COIN_CARD_ID
import lin.bean.usePlan.CardPurpose
import lin.bean.usePlan.PurposeTagId
import lin.serviceLoader.cardInfoProvide.COINProvide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-003：机制牌（硬币）用途硬编码注入合并测试。
 *
 * 机制语义不依赖 DB 配置：EXTRA_COST 标签取并集（用户配置不可删除机制标签），
 * 用户其余字段（replanAfterUse）保留。
 */
class PurposeStepTest {

    @Test
    fun `硬币机制用途表覆盖COIN_CARD_ID且为EXTRA_COST`() {
        assertEquals(setOf(PurposeTagId.EXTRA_COST), COINProvide.mechanismPurposes[COIN_CARD_ID]?.purposeTags)
    }

    @Test
    fun `空用户配置时硬币仍获得EXTRA_COST标签`() {
        val merged = PurposeStep.mergeMechanismPurposes(emptyMap())
        assertEquals(setOf(PurposeTagId.EXTRA_COST), merged[COIN_CARD_ID]?.purposeTags)
    }

    @Test
    fun `用户已配置硬币其他标签时取并集`() {
        val userPurposes = mapOf(
            COIN_CARD_ID to CardPurpose(purposeTags = setOf(PurposeTagId.VALUE), replanAfterUse = true)
        )
        val merged = PurposeStep.mergeMechanismPurposes(userPurposes)
        val coin = merged.getValue(COIN_CARD_ID)
        assertEquals(setOf(PurposeTagId.VALUE, PurposeTagId.EXTRA_COST), coin.purposeTags)
        // 用户字段保留，不被机制注入覆盖
        assertTrue(coin.replanAfterUse)
    }

    @Test
    fun `非机制牌配置原样透传`() {
        val userPurposes = mapOf(
            "NORMAL_001" to CardPurpose(purposeTags = setOf(PurposeTagId.CLEAN))
        )
        val merged = PurposeStep.mergeMechanismPurposes(userPurposes)
        assertEquals(userPurposes, merged.filterKeys { it != COIN_CARD_ID })
    }

    @Test
    fun `硬币额外费用数值通道保留coinKey`() {
        // T-004 仅迁识别方式，extCost() 数值仍读 cardContext[coinKey]
        val coinInfo = COINProvide().getInfos().getValue(COIN_CARD_ID)
        assertEquals(1, coinInfo.cardContext?.getMetadata(COINProvide.coinKey))
    }
}
