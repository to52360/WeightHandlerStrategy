package lin.mcp

import org.junit.Test
import java.nio.file.Path

/**
 * 真实演练 Stage 1:
 * 解析卡组代码，获取卡牌详情。不清理数据，把数据暴露给 AI 进行智能分组思考。
 *
 * ## 关键 ID 记录
 * - fileName: real_libram_deck
 * - 卡池卡牌: 从 get_card_group_detail 返回中读取
 */
class DeckFlowStage1_ParseTest : McpTestEnv() {

    companion object {
        const val FILE_NAME = "real_libram_deck"
        const val MANAGER_NAME = "real_libram_groups"
    }

    private val deckCode =
        "AAEBAZ8FBPfQArjFBdaABtK5Bg3YxwKd7ALZ/gL9uAPruQPTvQTi0wSZjgb1lQbt3waS4Aac6Aaf6AYAAA=="

    @Test
    fun parseAndFetchDetails() {
        // -- 前置：清理旧数据（含文件），确保干净起点 --
        cleanupAll(FILE_NAME, MANAGER_NAME, deleteFile = true)

        // 1. 探查能力面与正交积木
        println("========== 探查能力 ==========")
        call("list_capability_background")
        call("list_orthogonal_components")

        // 2. 解析卡组代码生成 .cardgroup
        println("========== 解析卡组 ==========")
        val parseResp = call(
            "parse_hearthstone_deck_code",
            """{"deckCode":"$deckCode","groupName":"$FILE_NAME","enabled":true}"""
        )
        if (!parseResp.isError) {
            val data = mapper.readValue(parseResp.contentJson, Map::class.java)
            savedFile = Path.of(System.getProperty("cardgroup.dir.path"), "$FILE_NAME.cardgroup")
            println(">>> 已保存文件: $savedFile")
            println(">>> 卡牌数量: ${data["totalCardsInCode"]}")
            println(">>> 职业: ${data["className"]}")
        }

        // 3. 获取卡牌详情供 AI 阅读
        println("========== 获取卡组详情供 AI 分析 ==========")
        val detailResp = call("card_pool", """{"action":"GET","fileName":"$FILE_NAME"}""")
        if (!detailResp.isError) {
            @Suppress("UNCHECKED_CAST")
            val detail = mapper.readValue(detailResp.contentJson, Map::class.java) as Map<String, Any>
            @Suppress("UNCHECKED_CAST")
            val cards = (detail["cards"] as? List<*>)?.filterIsInstance<Map<String, Any>>() ?: emptyList()
            println(">>> 卡组名: ${detail["name"]}")
            println(">>> 共 ${cards.size} 张卡牌")
            // 分类统计
            val byType = cards.groupBy { it["type"]?.toString() ?: "UNKNOWN" }
            byType.forEach { (type, list) ->
                println("  $type: ${list.size} 张")
                list.forEach { card ->
                    println("    ${card["cardId"]} | cost=${card["cost"]} | ${card["name"]}")
                }
            }
        }

        // 保存追踪信息
        saveTrackedIds(FILE_NAME, MANAGER_NAME, managerId, allTreeIds)

        // 注意：不调用 cleanup()，保留数据供 Stage 2 使用
    }
}
