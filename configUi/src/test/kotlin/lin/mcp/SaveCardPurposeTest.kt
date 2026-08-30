package lin.mcp

import com.fasterxml.jackson.databind.JsonNode
import lin.repository.card_purpose.CardPurposeRepository
import org.junit.Assert.*
import org.junit.Test
import org.koin.core.context.GlobalContext

/**
 * T-010（T-026 修订）：save_card_purpose 打标 + purpose_tag get 展示标签默认余费门槛 N。
 *
 * T-026 后 card-level candidatePolicy 已退役，save_card_purpose 只接受 purposeTags/replanAfterUse；
 * 惜售语义由标签默认 N（defaultSurplusIdleThreshold）承载，经 purpose_tag get 暴露给配置侧。
 *
 * 验证：①打标后可回读；②replanAfterUse null 不覆盖已存值；③未知标签拒绝；④purpose_tag get 展示 defaultSurplusIdleThreshold。
 */
class SaveCardPurposeTest : McpTestEnv() {

    // 必须是 hs.cards 真实存在的卡（findByCardId 主表是 hs.cards）
    private val cardIds = listOf("PET_2_1", "PET_2_2", "PET_2_3")

    @Test
    fun `save_card_purpose 保存用途标签并可回读`() {
        val cardId = cardIds[0]
        // 1. 保存打标 + replanAfterUse
        val save = call(
            "save_card_purpose",
            """{"cardIds":["$cardId"],"purposeTags":["DRAW_CARD"],"replanAfterUse":true}"""
        )
        assertEquals(false, save.isError)

        // 2. 从 DB 回读
        val repo = GlobalContext.get().get<CardPurposeRepository>()
        val entity = repo.findByCardId(cardId)
        assertNotNull("card_purpose 应能查回", entity)
        assertEquals("DRAW_CARD", entity!!.purposeTags)
        assertEquals(true, entity.replanAfterUse)
    }

    @Test
    fun `replanAfterUse null 不覆盖已存值`() {
        val cardId = cardIds[1]
        val repo = GlobalContext.get().get<CardPurposeRepository>()

        // 先存显式 replanAfterUse
        call(
            "save_card_purpose",
            """{"cardIds":["$cardId"],"purposeTags":["VALUE"],"replanAfterUse":true}"""
        )
        assertEquals(true, repo.findByCardId(cardId)!!.replanAfterUse)

        // 不传 replanAfterUse（null），应保留已存值
        call(
            "save_card_purpose",
            """{"cardIds":["$cardId"],"purposeTags":["VALUE","DRAW_CARD"]}"""
        )
        val after = repo.findByCardId(cardId)!!
        assertEquals(true, after.replanAfterUse)
        assertEquals(setOf("VALUE", "DRAW_CARD"), after.purposeTags.split(",").filter { it.isNotBlank() }.toSet())
    }

    @Test
    fun `非法标签拒绝保存`() {
        val cardId = cardIds[2]
        val save = call(
            "save_card_purpose",
            """{"cardIds":["$cardId"],"purposeTags":["NOT_A_TAG"]}"""
        )
        assertTrue(save.isError)
    }

    @Test
    fun `purpose_tag get 展示 defaultSurplusIdleThreshold`() {
        // Q-033 收窄后 N=1 仅保留给「晚出可能更有价值」的 SAVE_LIFE / CLEAN。
        // （原用 DRAW_CARD，但其 N 已改 null：过牌早过牌早赚，惜售与过牌目的相反）
        val detail = call("get", """{"resource":"purpose_tag","id":"SAVE_LIFE"}""")
        val json: JsonNode = mapper.readTree(detail.contentJson)
        assertEquals(1, json.get("defaultSurplusIdleThreshold")?.asInt())
    }

    @Test
    fun `过牌与成长标签不设惜售门槛`() {
        // Q-033：N 是局面属性而非用途固有属性，不再对战术标签一律置 1
        for (tag in listOf("DRAW_CARD", "GREED")) {
            val json: JsonNode = mapper.readTree(
                call("get", """{"resource":"purpose_tag","id":"$tag"}""").contentJson
            )
            assertTrue(
                "$tag 不应设 N（期望 null）",
                json.get("defaultSurplusIdleThreshold")?.isNull ?: true
            )
        }
    }
}
