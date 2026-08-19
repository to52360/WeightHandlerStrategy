package lin.mcp

import com.fasterxml.jackson.databind.JsonNode
import lin.repository.card_purpose.CardPurposeRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.koin.core.context.GlobalContext

/**
 * T-010：save_card_purpose 支持 candidatePolicy 三态持久化。
 *
 * 验证：①显式 candidatePolicy 保存后可回读；②null（不传）不覆盖已存值；③purpose_tag get 展示。
 */
class SaveCardPurposeCandidatePolicyTest : McpTestEnv() {

    // 必须是 hs.cards 真实存在的卡（findByCardId 主表是 hs.cards）
    private val cardIds = listOf("PET_2_1", "PET_2_2", "PET_2_3")

    @Test
    fun `save_card_purpose 保存 candidatePolicy 并可回读`() {
        val cardId = cardIds[0]
        // 1. 保存带候选策略
        val save = call(
            "save_card_purpose",
            """{"cardIds":["$cardId"],"purposeTags":["DRAW_CARD"],"candidatePolicy":"TACTICS_DOMINANT"}"""
        )
        assertEquals(false, save.isError)

        // 2. 从 DB 回读
        val repo = GlobalContext.get().get<CardPurposeRepository>()
        val entity = repo.findByCardId(cardId)
        assertNotNull("card_purpose 应能查回", entity)
        assertEquals("TACTICS_DOMINANT", entity!!.candidatePolicy?.name)
        assertEquals("DRAW_CARD", entity.purposeTags)

        // 3. purpose_tag get 展示 defaultCandidatePolicy（DRAW_CARD 标签引擎侧预设 TACTICS_DOMINANT）
        val detail = call("get", """{"resource":"purpose_tag","id":"DRAW_CARD"}""")
        val json: JsonNode = mapper.readTree(detail.contentJson)
        assertEquals("TACTICS_DOMINANT", json.get("defaultCandidatePolicy")?.asText())
    }

    @Test
    fun `candidatePolicy null 不覆盖已存值`() {
        val cardId = cardIds[1]
        val repo = GlobalContext.get().get<CardPurposeRepository>()

        // 先存显式值
        call(
            "save_card_purpose",
            """{"cardIds":["$cardId"],"purposeTags":["VALUE"],"candidatePolicy":"NORMAL"}"""
        )
        assertEquals("NORMAL", repo.findByCardId(cardId)!!.candidatePolicy?.name)

        // 不传 candidatePolicy（null），应保留已存值
        call(
            "save_card_purpose",
            """{"cardIds":["$cardId"],"purposeTags":["VALUE","DRAW_CARD"]}"""
        )
        val after = repo.findByCardId(cardId)!!
        assertEquals("NORMAL", after.candidatePolicy?.name)
        assertEquals(setOf("VALUE", "DRAW_CARD"), after.purposeTags.split(",").filter { it.isNotBlank() }.toSet())
    }

    @Test
    fun `非法 candidatePolicy 拒绝保存`() {
        val cardId = cardIds[2]
        val save = call(
            "save_card_purpose",
            """{"cardIds":["$cardId"],"purposeTags":["VALUE"],"candidatePolicy":"NOT_A_POLICY"}"""
        )
        assertEquals(true, save.isError)
    }
}
