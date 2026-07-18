package lin.mcp

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 真实演练 Stage 2 (v2):
 * 基于炉石领域知识的策略角色分组（非机械属性分类）。
 *
 * 6 组方案：
 * 1. 圣契减费引擎 — BT_020, GDB_726
 * 2. 圣契法术 — BT_025, GDB_137, GDB_138
 * 3. 过牌检索 — BOT_909, ETC_418, UNG_961, GDB_728, TID_098
 * 4. 干扰拖延 — JAM_006, GIL_203
 * 5. 解场清理 — WW_051, WW_336
 * 6. 制胜终端 — ICC_820, VAC_507, TID_077
 *
 * ## 关键 ID 记录
 * - managerId: 返回的 manager 主键
 * - bindingIds: 各分组的 binding ID 列表
 */
class DeckFlowStage2_GroupTest : McpTestEnv() {

    companion object {
        const val FILE_NAME = "real_libram_deck"
        const val MANAGER_NAME = "real_libram_groups"
    }

    @Test
    fun saveCardGroups() {
        // -- 前置：清理旧分组数据 --
        cleanupAll(FILE_NAME, MANAGER_NAME)

        println("========== AI 领域知识分组 (v2) ==========")
        val bindingsJson = """[
            {
              "name":"圣契减费引擎",
              "cardIds":["BT_020","GDB_726"],
              "description":"通过随从（如奥尔多侍从）为本局游戏中的圣契法术进行永久减费，是卡组平稳运行的动力源"
            },
            {
              "name":"圣契法术",
              "cardIds":["BT_025","GDB_137","GDB_138"],
              "description":"核心圣契系列法术，减费后可反复使用，提供控场、Buff和生命回复"
            },
            {
              "name":"过牌检索",
              "cardIds":["BOT_909","ETC_418","UNG_961","GDB_728","TID_098"],
              "description":"用于过滤牌库、定向检索圣契组件或过牌的辅助随从与法术"
            },
            {
              "name":"干扰拖延",
              "cardIds":["JAM_006","GIL_203"],
              "description":"通过破坏对手手牌/手牌随从或拖延节奏，提供防御和战术反制"
            },
            {
              "name":"解场清理",
              "cardIds":["WW_051","WW_336"],
              "description":"用于清理敌方场面、延缓攻势、重新夺回主动权的解场法术或解场随从"
            },
            {
              "name":"制胜终端",
              "cardIds":["ICC_820","VAC_507","TID_077"],
              "description":"在对局中后期提供资源斩杀、打出制胜爆发或提供厚度压制的核心终端卡牌"
            }
        ]"""

        val saveResp = call(
            "save_card_group", """{
            "sourceFile":"$FILE_NAME",
            "managerName":"$MANAGER_NAME",
            "bindings":$bindingsJson
        }"""
        )
        assertTrue("save_card_group should succeed", !saveResp.isError)

        val saveData = mapper.readValue(saveResp.contentJson, Map::class.java)
        managerId = saveData["managerId"] as? String ?: error("no managerId")
        @Suppress("UNCHECKED_CAST")
        bindingIds = (saveData["bindingIds"] as? List<*>)?.map { it.toString() } ?: emptyList()

        println("========================================")
        println(">>> 成功创建 6 组策略角色分组！")
        println(">>> Manager ID: $managerId")
        bindingIds.forEachIndexed { i, id ->
            println(">>> Binding[$i]: $id")
        }
        println("========================================")

        // 验证：读取刚创建的 manager
        val mgrResp = call("card_group", """{"action":"GET","managerId":"$managerId"}""")
        assertTrue("card_group GET 应成功", !mgrResp.isError)
        @Suppress("UNCHECKED_CAST")
        val mgrData = mapper.readValue(mgrResp.contentJson, Map::class.java)
        @Suppress("UNCHECKED_CAST")
        val bindings = mgrData["bindings"] as List<Map<String, Any>>

        // 验证分组数量
        assertTrue("应该正好 6 组", bindings.size == 6)

        // 验证每组 cardId 数量
        val expected = mapOf(
            "圣契减费引擎" to 2,
            "圣契法术" to 3,
            "过牌检索" to 5,
            "干扰拖延" to 2,
            "解场清理" to 2,
            "制胜终端" to 3
        )
        for (b in bindings) {
            val name = b["name"] as String
            @Suppress("UNCHECKED_CAST")
            val cardIds = b["cardIds"] as List<String>
            val exp = expected[name] ?: error("未知组名: $name")
            assertTrue("$name: 期望 $exp 张, 实际 ${cardIds.size}", cardIds.size == exp)
            println("  ✅ $name: $cardIds")
        }

        // 保存追踪信息
        saveTrackedIds(FILE_NAME, MANAGER_NAME, managerId, allTreeIds)
    }
}
