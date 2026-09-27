package lin.mcp

import lin.dao.CardGroupJsonParser
import lin.dao.CardWeightConfig
import org.junit.Assert.*
import org.junit.Test

/**
 * T-FO-018（K-FO-011）：惜售门槛 N 的**写侧可满足性提示**行为验证。
 *
 * 事实：放行条件 = 「空闲 ≥ 牌费 + N」，空闲上限 = 法力上限 10 ⇒ 牌费 + N > 10 的牌在未命中战术时垫不出。
 * 但写侧只能拿到**数据库初始费**，而引擎判定用**实时费**（减费卡更低）⇒ 只**提示不阻断**
 * （`SaveCardGroupToolProvider` / `save_card_pool_weights` 返回 `warnings`，见 `SurplusGateValidator` KDoc）。
 *
 * 用 AV_108（裂盾一击，数据库初始费 10）：N=1 ⇒ 初始费+N = 11 > 10 ⇒ 必出提示。
 * 环境（Koin / DB / 工具注册 / 清理）复用 [McpTestEnv]；本类只写断言。
 */
class TFO018SurplusGateFeasibilityTest : McpTestEnv() {

    private val groupName = "tfo018_gate_feasibility_deck"
    private val managerName = "tfo018_gate_feasibility_groups"

    /** 数据库初始费 10 的卡（提示命中；其实际费可能被减费改变）。 */
    private val expensiveCard = "AV_108"
    private val expensiveCardName = "裂盾一击"

    /** 造一个只含该卡的卡池文件。 */
    private fun createPool(): String {
        val path = CardGroupJsonParser.saveCardGroupConfigs(
            configs = listOf(CardWeightConfig(cardId = expensiveCard, name = expensiveCardName)),
            groupName = groupName,
            enabled = true
        )
        savedFile = path
        return groupName
    }

    @Suppress("UNCHECKED_CAST")
    private fun warningsOf(resp: McpToolResult): List<String> {
        val body = mapper.readValue(resp.contentJson, Map::class.java) as Map<String, Any?>
        return (body["warnings"] as? List<Any?>).orEmpty().map { it.toString() }
    }

    /** 分组级：初始费 + N 超上限 ⇒ **保存成功**但带 warnings（不阻断）。 */
    @Test
    fun groupGate_infeasibleThresholdWarnsButSaves() {
        val file = createPool()
        val resp = call(
            "save_card_group",
            """{"sourceFile":"$file","managerName":"$managerName","bindings":[
                {"name":"高费守卫组","cardIds":["$expensiveCard"],"surplusIdleThreshold":1}]}"""
        )
        assertFalse("不应阻断保存（初始费 ≠ 实时费，减费卡可能可满足）：${resp.contentJson}", resp.isError)
        @Suppress("UNCHECKED_CAST")
        val body = mapper.readValue(resp.contentJson, Map::class.java) as Map<String, Any?>
        managerId = body["managerId"] as? String

        val warnings = warningsOf(resp)
        assertEquals("应产出 1 条提示", 1, warnings.size)
        assertTrue(
            "提示应含卡 id 与「请自行确认」措辞：$warnings",
            warnings[0].contains(expensiveCard) && warnings[0].contains("请自行确认")
        )
    }

    /** 分组级对照组：不设门槛（N=0）⇒ 无提示，证明提示来自「初始费 + N > 上限」。 */
    @Test
    fun groupGate_feasibleThresholdHasNoWarning() {
        val file = createPool()
        val resp = call(
            "save_card_group",
            """{"sourceFile":"$file","managerName":"$managerName","bindings":[
                {"name":"高费自由组","cardIds":["$expensiveCard"]}]}"""
        )
        assertFalse("不设门槛应可保存：${resp.contentJson}", resp.isError)
        @Suppress("UNCHECKED_CAST")
        val body = mapper.readValue(resp.contentJson, Map::class.java) as Map<String, Any?>
        managerId = body["managerId"] as? String
        assertNull("不设门槛不应产出 warnings 字段", body["warnings"])
    }

    /** 逐卡通道：同样只提示不阻断。 */
    @Test
    fun perCardGate_infeasibleThresholdWarnsButSaves() {
        val file = createPool()
        val resp = call(
            "save_card_pool_weights",
            """{"fileName":"$file","cards":[{"cardId":"$expensiveCard","surplusIdleThreshold":1}]}"""
        )
        assertFalse("不应阻断保存：${resp.contentJson}", resp.isError)
        val warnings = warningsOf(resp)
        assertEquals("应产出 1 条提示", 1, warnings.size)
        assertTrue(
            "提示应含卡 id：$warnings",
            warnings[0].contains(expensiveCard)
        )
    }
}
