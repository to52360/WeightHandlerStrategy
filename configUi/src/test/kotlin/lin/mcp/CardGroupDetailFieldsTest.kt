package lin.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 get_card_group_detail 返回完整的卡牌游戏属性。
 */
class CardGroupDetailFieldsTest : McpTestEnv() {

    private val deckCode =
        "AAEBAZ8FBPfQArjFBdaABtK5Bg3YxwKd7ALZ/gL9uAPruQPTvQTi0wSZjgb1lQbt3waS4Aac6Aaf6AYAAA=="

    @Test
    fun detailReturnsFullCardProperties() {
        try {
            // 解析卡组 → 生成 .cardgroup
            call(
                "parse_hearthstone_deck_code",
                """{"deckCode":"$deckCode","groupName":"detail_verify","enabled":true}"""
            )
            savedFile = java.nio.file.Path.of("../data/cardgroup/detail_verify.cardgroup")

            // 查询详情
            val resp = call("get_card_group_detail", """{"fileName":"detail_verify"}""")
            assertTrue("should succeed", !resp.isError)

            val data = mapper.readValue(resp.contentJson, MutableMap::class.java)

            @Suppress("UNCHECKED_CAST")
            val cards = data["cards"] as? List<Map<String, Any?>> ?: emptyList()

            // 验证 ICC_820 属性正确
            val icc = cards.find { it["cardId"] == "ICC_820" } ?: error("ICC_820 not found")
            assertEquals("cost", 4, icc["cost"])
            assertEquals("type", "MINION", icc["type"])
            assertEquals("attack", 3, icc["attack"])
            assertEquals("health", 2, icc["health"])
            assertEquals("race", "UNDEAD", icc["race"])
            assertEquals("cardClass", "PALADIN", icc["cardClass"])

            // 验证法术卡 UNG_961 属性（法术无 attack/health）
            val ung = cards.find { it["cardId"] == "UNG_961" } ?: error("UNG_961 not found")
            assertEquals("spell cost", 0, ung["cost"])
            assertEquals("spell type", "SPELL", ung["type"])
            assertEquals("spell attack", 0, ung["attack"])
            assertEquals("spell health", 0, ung["health"])

            println("\n✅ get_card_group_detail 正确返回 cost/type/attack/health/race/cardClass")
        } finally {
            cleanup()
        }
    }
}
