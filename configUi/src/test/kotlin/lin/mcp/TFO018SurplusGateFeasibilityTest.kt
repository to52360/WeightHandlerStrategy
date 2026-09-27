package lin.mcp

import lin.dao.CardGroupJsonParser
import lin.dao.CardWeightConfig
import org.junit.Assert.*
import org.junit.Test

/**
 * T-FO-018（K-FO-011）：惜售门槛 N 的**写侧可满足性校验**行为验证。
 *
 * 事实：放行条件 = 「空闲 ≥ 牌费 + N」，空闲上限 = 法力上限 10 ⇒ `牌费 + N > 10` 的牌
 * 在未命中战术时**永远垫不出**（静默硬禁）⇒ 两个能确定牌集合的写入口保存时即拒收：
 * - `save_card_group` 的分组级 `surplusIdleThreshold`（静态成员逐张校验）
 * - `save_card_pool_weights` 的逐卡 `surplusIdleThreshold`
 *
 * 用 AV_108（裂盾一击，10 费）：N=1 ⇒ 费+N = 11 > 10，必违规。
 * 环境（Koin / DB / 工具注册 / 清理）复用 [McpTestEnv]；本类只写断言。
 */
class TFO018SurplusGateFeasibilityTest : McpTestEnv() {

    private val groupName = "tfo018_gate_feasibility_deck"
    private val managerName = "tfo018_gate_feasibility_groups"

    /** 10 费卡（费 + N=1 > 法力上限 10）。 */
    private val expensiveCard = "AV_108"
    private val expensiveCardName = "裂盾一击"

    /** 造一个只含 10 费卡的卡池文件，返回其路径（登记 cleanup）。 */
    private fun createPoolWithExpensiveCard(): String {
        val path = CardGroupJsonParser.saveCardGroupConfigs(
            configs = listOf(CardWeightConfig(cardId = expensiveCard, name = expensiveCardName)),
            groupName = groupName,
            enabled = true
        )
        savedFile = path
        return groupName
    }

    @Test
    fun groupGate_unfeasibleThresholdRejected() {
        val file = createPoolWithExpensiveCard()
        val resp = call(
            "save_card_group",
            """{"sourceFile":"$file","managerName":"$managerName","bindings":[
                {"name":"高费守卫组","cardIds":["$expensiveCard"],"surplusIdleThreshold":1}]}"""
        )
        assertTrue("费 10 + N 1 的门槛应被拒收：${resp.contentJson}", resp.isError)
        assertTrue(
            "错误信息应含「不可满足」与违规卡 id：${resp.contentJson}",
            resp.contentJson.contains("不可满足") && resp.contentJson.contains(expensiveCard)
        )
    }

    @Test
    fun groupGate_feasibleThresholdAccepted() {
        val file = createPoolWithExpensiveCard()
        // 对照组：同一张 10 费卡配 N=0 语义（不传门槛）应可保存 —— 证明拒收来自费+N>上限，而非该卡本身
        val resp = call(
            "save_card_group",
            """{"sourceFile":"$file","managerName":"$managerName","bindings":[
                {"name":"高费自由组","cardIds":["$expensiveCard"]}]}"""
        )
        assertFalse("不设门槛（N=0）应可保存：${resp.contentJson}", resp.isError)
        @Suppress("UNCHECKED_CAST")
        val body = mapper.readValue(resp.contentJson, Map::class.java) as Map<String, Any?>
        managerId = body["managerId"] as? String
        assertNotNull("应返回 managerId", managerId)
    }

    @Test
    fun perCardGate_unfeasibleThresholdRejected() {
        val file = createPoolWithExpensiveCard()
        val resp = call(
            "save_card_pool_weights",
            """{"fileName":"$file","cards":[{"cardId":"$expensiveCard","surplusIdleThreshold":1}]}"""
        )
        assertTrue("逐卡费 10 + N 1 应被拒收：${resp.contentJson}", resp.isError)
        assertTrue(
            "错误信息应含「不可满足」与违规卡 id：${resp.contentJson}",
            resp.contentJson.contains("不可满足") && resp.contentJson.contains(expensiveCard)
        )
    }
}
