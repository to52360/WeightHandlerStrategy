package lin.mcp

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * T-011 回归（sop-rework，2026-09-05）：Provider 层事务下沉服务层后行为不变。
 *
 * - save_aura_boost：内联建树（0~2 棵）+ boost 行由 [lin.repository.aura_boost.AuraBoostConfigService.saveWithInlineTrees]
 *   在服务层事务编排，MCP Provider 不再注入 TransactionTemplate / ConditionTreeConfigService。
 * - delete(card_group)：级联删由 [lin.ui.service.CardGroupCascadeDeleteService] 完成，快照结构不变。
 * - 校验失败（conditionId 与 treeJson 互斥）经中立 [lin.repository.condition_tree.ConditionTreeReferenceException]
 *   以原消息返回，且**事务回滚**（先建的 trigger 树不残留）。
 */
class T011ProviderTxSinkTest : McpTestEnv() {

    private val deckCode =
        "AAEBAZ8FBPfQArjFBdaABtK5Bg3YxwKd7ALZ/gL9uAPruQPTvQTi0wSZjgb1lQbt3waS4Aac6Aaf6AYAAA=="
    private val managerName = "t011_tx_sink_groups"

    // 「所有法术」条件树：evaluating_card → to_card → is_card_type(SPELL)（与 SavePredicateGroupTest 同构，inlineCreated 参数校验可过）
    private val spellTreeJson =
        """{"id":"t005_spell_tree","name":"t005_spell_tree","root":{"Leaf":{"payload":{"PipelineRef":{"refId":"t1","sourceId":"evaluating_card","transforms":[{"transformId":"to_card","args":{}}],"operatorId":"is_card_type","operatorArgs":{"targetType":"SPELL"}}}}}}"""
    private val rollbackTreeJson =
        """{"id":"t011_rollback_tree","name":"t011_rollback_trigger_tree","root":{"Leaf":{"payload":{"PipelineRef":{"refId":"r1","sourceId":"evaluating_card","transforms":[{"transformId":"to_card","args":{}}],"operatorId":"is_card_type","operatorArgs":{"targetType":"SPELL"}}}}}}"""

    @Before
    fun ensureCardPoolAndClean() {
        cleanupAll("verify_deck_1", managerName)
        val resp = call(
            "parse_hearthstone_deck_code",
            """{"deckCode":"$deckCode","groupName":"verify_deck_1","enabled":true}"""
        )
        assertFalse("卡池文件创建失败: ${resp.contentJson}", resp.isError)
    }

    private fun createManagerWithBinding(): String {
        val resp = call(
            "save_card_group",
            """{"sourceFile":"verify_deck_1","managerName":"$managerName","bindings":[{"name":"T011守卫组","cardIds":["ICC_820"]}]}"""
        )
        assertFalse("建方案应成功: ${resp.contentJson}", resp.isError)
        @Suppress("UNCHECKED_CAST")
        val m = mapper.readValue(resp.contentJson, Map::class.java)
        val mid = m["managerId"] as String
        managerId = mid
        return mid
    }

    /** T-011a：save_aura_boost 内联建树由服务层事务编排，落库可回读。 */
    @Test
    fun `saveAuraBoost 内联建树经服务事务编排落库`() {
        val mid = createManagerWithBinding()
        val resp = call(
            "save_aura_boost",
            """{
              "name":"t011_boost_inline",
              "score":2.0,
              "managerId":"$mid",
              "conditionTreeJson":${mapper.writeValueAsString(spellTreeJson)},
              "targetConditionTreeJson":${mapper.writeValueAsString(spellTreeJson)}
            }"""
        )
        assertFalse("save_aura_boost 应成功: ${resp.contentJson}", resp.isError)
        @Suppress("UNCHECKED_CAST")
        val m = mapper.readValue(resp.contentJson, Map::class.java)
        val id = m["id"] as String
        val conditionId = m["conditionId"] as String
        val targetConditionId = m["targetConditionId"] as String
        assertTrue("应生成触发条件树 id", conditionId.isNotBlank())
        assertTrue("应生成受益过滤条件树 id", targetConditionId.isNotBlank())

        // 回读落库
        val getResp = call("get", """{"resource":"aura_boost","id":"$id"}""")
        assertFalse("get(aura_boost) 应成功: ${getResp.contentJson}", getResp.isError)
        @Suppress("UNCHECKED_CAST")
        val loaded = mapper.readValue(getResp.contentJson, Map::class.java)
        assertEquals("t011_boost_inline", loaded["name"])
        assertEquals(conditionId, loaded["conditionId"])
        assertEquals(targetConditionId, loaded["targetConditionId"])
        assertEquals(2.0, loaded["score"])

        // 清理 boost 行（内联树挂 manager 下，由 @After cleanup 级联清理）
        val del = call("delete", """{"resource":"aura_boost","id":"$id"}""")
        assertFalse("清理 aura_boost 应成功: ${del.contentJson}", del.isError)
    }

    /** T-011a：互斥校验在服务事务内抛出 → 已建的 trigger 树随事务回滚不残留。 */
    @Test
    fun `saveAuraBoost 互斥校验失败回滚已建树`() {
        val mid = createManagerWithBinding()
        val resp = call(
            "save_aura_boost",
            """{
              "name":"t011_rollback_ab",
              "score":2.0,
              "managerId":"$mid",
              "conditionTreeJson":${mapper.writeValueAsString(rollbackTreeJson)},
              "targetConditionId":"some_ref",
              "targetConditionTreeJson":${mapper.writeValueAsString(spellTreeJson)}
            }"""
        )
        assertTrue("conditionId 与 treeJson 互斥应报错: ${resp.contentJson}", resp.isError)
        assertTrue("错误消息应说明互斥: ${resp.contentJson}", resp.contentJson.contains("互斥"))

        // trigger 树已由 resolver 建出但在事务内 → 抛错后必须回滚，全库不应有该内联树残留
        val listResp = call("list", """{"resource":"condition_tree"}""")
        assertFalse(listResp.isError)
        @Suppress("UNCHECKED_CAST")
        val trees = mapper.readValue(listResp.contentJson, List::class.java) as List<Map<String, Any?>>
        assertTrue(
            "互斥失败后不应残留 trigger 树（事务已回滚），实际残留: $trees",
            trees.none { (it["name"] as? String) == "t011_rollback_trigger_tree" }
        )
    }

    /** T-011b：delete(card_group) 级联删除由应用服务完成，快照结构不变。 */
    @Test
    fun `deleteCardGroup 级联删除返回快照`() {
        val mid = createManagerWithBinding()
        val resp = call("delete", """{"resource":"card_group","id":"$mid"}""")
        assertFalse("delete(card_group) 应成功: ${resp.contentJson}", resp.isError)
        @Suppress("UNCHECKED_CAST")
        val m = mapper.readValue(resp.contentJson, Map::class.java)
        assertEquals(true, m["deleted"])
        assertEquals(mid, m["managerId"])
        assertEquals(listOf("T011守卫组"), m["deletedBindings"])
        assertEquals(2, m["totalDeleted"])

        // 删除后回读应不存在
        val getResp = call("get", """{"resource":"card_group","id":"$mid"}""")
        assertTrue("删除后 get 应报不存在: ${getResp.contentJson}", getResp.isError)
    }
}
