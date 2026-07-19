package lin.mcp

import org.junit.Test

/**
 * 查询 real_libram_deck 卡池完整元数据测试。
 */
class DeckCardMetadataQueryTest : McpTestEnv() {

    @Test
    fun queryGroup2CardDetails() {
        println("========== 探查 real_libram_deck 卡池数据 ==========")
        val resp = call("card_pool", """{"action":"GET","fileName":"real_libram_deck"}""")
        println(">>> card_pool GET 结果:")
        println(resp.contentJson)
    }
}
