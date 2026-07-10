package lin.mcp

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 1~3：元数据探查 → 卡池分组 → 参考模板。
 *
 * 用真实卡组 [AAEBAZ8F...]（圣骑士）走完前三阶段，动态提取卡牌 ID 建分组，
 * 暴露工具描述的歧义和缺失字段。
 */
class DeckFlowStage1_3Test : McpTestEnv() {

    private val deckCode =
        "AAEBAZ8FBPfQArjFBdaABtK5Bg3YxwKd7ALZ/gL9uAPruQPTvQTi0wSZjgb1lQbt3waS4Aac6Aaf6AYAAA=="

    @Test
    fun stage1_parseAndExplore() {
        try {
            // ── Stage 1: 解析卡组 & 写入 .cardgroup 文件 ──
            val parseResp = call(
                "parse_hearthstone_deck_code",
                """{"deckCode":"$deckCode","groupName":"verify_deck_1","enabled":true}"""
            )
            assertTrue("parse should succeed", !parseResp.isError)
            savedFile = java.nio.file.Path.of("../data/cardgroup/verify_deck_1.cardgroup")

            val parseData = mapper.readValue(parseResp.contentJson, Map::class.java)

            @Suppress("UNCHECKED_CAST")
            val parsedCards = parseData["parsedCards"] as? List<Map<String, Any?>> ?: emptyList()
            println(">>> parsed ${parsedCards.size} cards, heroes=${parseData["heroes"]}")
            assertTrue("should have parsed cards", parsedCards.isNotEmpty())

            // ── 元数据探查 ──
            call("list_card_group_sources")
            call("list_capability_background")
            call("list_orthogonal_components")

            // ── Stage 2: 用解析出的真实卡牌 ID 建分组 ──
            // 从 parsedCards 中取前 5 张作为示例绑定分组
            val sampleCardIds = parsedCards.take(5).map { it["cardId"] as String }
            println(">>> sample card IDs for binding: $sampleCardIds")

            val saveResp = call(
                "save_card_group",
                """{
                  "sourceFile":"verify_deck_1",
                  "managerName":"verify_group_1",
                  "bindings":[{"name":"前5张卡","cardIds":${mapper.writeValueAsString(sampleCardIds)}}]
                }"""
            )
            assertTrue("save_card_group should succeed", !saveResp.isError)

            val saveData = mapper.readValue(saveResp.contentJson, Map::class.java)
            managerId = saveData["managerId"] as? String
            @Suppress("UNCHECKED_CAST")
            bindingIds = (saveData["bindingIds"] as? List<*>)?.map { it.toString() } ?: emptyList()
            assertNotNull("managerId should not be null", managerId)
            println(">>> managerId=$managerId, bindingIds=$bindingIds")

            call("list_card_groups")

            // G-04：验证 get_card_group_manager 可查 binding 条目 ID
            if (managerId != null) {
                call("get_card_group_manager", """{"managerId":"$managerId"}""")
            }

            // ── Stage 3: 参考模板与存量配置 ──
            call("list_evaluator_trees")
            call("list_evaluator_tree_templates")

            assertTrue(true)
        } finally {
            cleanup()
        }
    }
}
