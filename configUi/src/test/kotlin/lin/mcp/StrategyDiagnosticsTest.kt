package lin.mcp

import lin.dao.CardGroupJsonParser
import lin.dao.CardWeightConfig
import org.junit.Assert.*
import org.junit.Test

/**
 * T-FO-019（Q-FO-004 层 1 第一刀）：`strategy_diagnostics` 工具行为验证。
 *
 * 覆盖：① 工具注册 + 输出结构（scope / checks / caveats 齐备）；② 能报出生效 N 超限的卡
 * （并给出**生效来源** basis）；③ 无超限时不误报。
 *
 * 用 AV_108（裂盾一击，数据库初始费 10）+ 分组级 N=1 ⇒ 10+1 > 法力上限 10 ⇒ 必被列出。
 * 环境（Koin / DB / 工具注册 / 清理）复用 [McpTestEnv]；本类只写断言。
 */
class StrategyDiagnosticsTest : McpTestEnv() {

    private val groupName = "tfo019_diag_deck"
    private val managerName = "tfo019_diag_groups"
    private val expensiveCard = "AV_108"

    /** 造卡池（含 10 费卡），返回卡池文件名。 */
    private fun createPool(): String {
        savedFile = CardGroupJsonParser.saveCardGroupConfigs(
            configs = listOf(CardWeightConfig(cardId = expensiveCard, name = "裂盾一击")),
            groupName = groupName,
            enabled = true
        )
        return groupName
    }

    /** 建带分组级门槛的卡组，返回 managerId。 */
    private fun createGroupWithGate(gate: Int?, file: String): String {
        val gateJson = if (gate == null) "" else ""","surplusIdleThreshold":$gate"""
        val resp = call(
            "save_card_group",
            """{"sourceFile":"$file","managerName":"$managerName","bindings":[
                {"name":"守卫组","cardIds":["$expensiveCard"]$gateJson}]}"""
        )
        assertFalse("建卡组应成功：${resp.contentJson}", resp.isError)
        @Suppress("UNCHECKED_CAST")
        val body = mapper.readValue(resp.contentJson, Map::class.java) as Map<String, Any?>
        val id = body["managerId"] as? String
        assertNotNull("应返回 managerId", id)
        managerId = id
        return id!!
    }

    @Suppress("UNCHECKED_CAST")
    private fun checksOf(resp: McpToolResult): List<Map<String, Any?>> {
        val body = mapper.readValue(resp.contentJson, Map::class.java) as Map<String, Any?>
        return (body["checks"] as? List<Map<String, Any?>>).orEmpty()
    }

    /** ① 工具注册 + 输出结构：每项都必须带 caveats（近似边界声明是本工具的契约）。 */
    @Test
    fun `输出结构完整且每项带近似边界声明`() {
        assertTrue("strategy_diagnostics 应已注册", tools.containsKey("strategy_diagnostics"))

        val resp = call("strategy_diagnostics", """{}""")
        assertFalse("体检应成功：${resp.contentJson}", resp.isError)

        @Suppress("UNCHECKED_CAST")
        val body = mapper.readValue(resp.contentJson, Map::class.java) as Map<String, Any?>
        assertNotNull("应返回 scope", body["scope"])
        val checks = checksOf(resp)
        assertTrue("应至少包含一项体检", checks.isNotEmpty())
        assertEquals("首项应为余费门槛可满足性", "surplusFeasibility", checks[0]["id"])
        assertTrue(
            "体检项必须声明近似边界（caveats 非空）",
            (checks[0]["caveats"] as? List<*>).orEmpty().isNotEmpty()
        )
    }

    /** ② 分组级 N=1 + 初始费 10 ⇒ 应被列出，且给出生效来源（basis 指分组层）。 */
    @Test
    fun `报出生效 N 超限的卡并标注生效来源`() {
        val file = createPool()
        createGroupWithGate(gate = 1, file = file)

        val resp = call("strategy_diagnostics", """{"managerId":"$managerId"}""")
        assertFalse("体检应成功：${resp.contentJson}", resp.isError)

        val items = (checksOf(resp).first()["items"] as? List<Map<String, Any?>>).orEmpty()
        val hit = items.firstOrNull { it["cardId"] == expensiveCard }
        assertNotNull("应列出 $expensiveCard：${resp.contentJson}", hit)
        assertEquals("生效 N 应为分组层给的 1", 1, hit!!["effectiveN"])
        assertTrue(
            "应标注生效来源为分组层：${hit["effectiveBasis"]}",
            hit["effectiveBasis"].toString().contains("分组")
        )
        assertEquals("应带上数据库初始费", 10, hit["dataBaseCost"])
    }

    /** ③ 对照组：不设门槛（N=0）⇒ 不误报。 */
    @Test
    fun `不设门槛时不误报`() {
        val file = createPool()
        createGroupWithGate(gate = null, file = file)

        val resp = call("strategy_diagnostics", """{"managerId":"$managerId"}""")
        assertFalse("体检应成功：${resp.contentJson}", resp.isError)

        val items = (checksOf(resp).first()["items"] as? List<Map<String, Any?>>).orEmpty()
        assertTrue("不应报出任何卡：$items", items.isEmpty())
    }
}
