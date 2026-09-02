package lin.mcp

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * T-005 验证：`save_card_group` 支持创建谓词组（条件定义成员）。
 *
 * 覆盖：①内联 treeJson 建谓词组 ②引用已有条件树建谓词组 ③memberType/conditionId/includeDerived 输出
 * ④includeDerived 透传与缺省 ⑤静态组回退不误伤 ⑥卡池校验对谓词组跳过 ⑦条件树互斥校验。
 */
class SavePredicateGroupTest : McpTestEnv() {

    // 「所有法术」条件树：evaluating_card → to_card → is_card_type(SPELL)
    private val spellTreeJson =
        """{"id":"t005_spell_tree","name":"t005_spell_tree","root":{"Leaf":{"payload":{"PipelineRef":{"refId":"t1","sourceId":"evaluating_card","transforms":[{"transformId":"to_card","args":{}}],"operatorId":"is_card_type","operatorArgs":{"targetType":"SPELL"}}}}}}"""

    private val deckCode =
        "AAEBAZ8FBPfQArjFBdaABtK5Bg3YxwKd7ALZ/gL9uAPruQPTvQTi0wSZjgb1lQbt3waS4Aac6Aaf6AYAAA=="

    @Before
    fun ensureCardPool() {
        // 幂等创建卡池文件（parse_hearthstone_deck_code 会覆盖同名文件，内容一致无害）
        val resp = call(
            "parse_hearthstone_deck_code",
            """{"deckCode":"$deckCode","groupName":"verify_deck_1","enabled":true}"""
        )
        assertFalse("卡池文件创建失败: ${resp.contentJson}", resp.isError)
    }

    @Test
    fun `内联条件树创建谓词组`() {
        cleanupAll("verify_deck_1", "t005_pred_inline")

        val resp = call(
            "save_card_group",
            """{
              "sourceFile":"verify_deck_1",
              "managerName":"t005_pred_inline",
              "bindings":[
                {"name":"法术组","conditionTreeJson":${mapper.writeValueAsString(spellTreeJson)},"includeDerived":true}
              ]
            }"""
        )
        assertFalse("应创建成功: ${resp.contentJson}", resp.isError)
        val m = mapper.readValue(resp.contentJson, Map::class.java)
        val managerId = m["managerId"] as String
        val bindings = m["bindings"] as List<Map<String, Any?>>

        assertEquals(1, bindings.size)
        assertEquals("PREDICATE", bindings[0]["memberType"])
        // 内联树生成了 8 位短 id，非空即解析成功
        assertTrue((bindings[0]["conditionId"] as String).isNotBlank())
        assertEquals(true, bindings[0]["includeDerived"])
        // 谓词组 cardIds 恒空
        assertEquals(emptyList<String>(), bindings[0]["cardIds"])

        // 回读验证 memberType/conditionId 持久化正确
        val getResp = call("get", """{"resource":"card_group","id":"$managerId"}""")
        assertFalse(getResp.isError)
        val loaded = mapper.readValue(getResp.contentJson, Map::class.java)

        @Suppress("UNCHECKED_CAST")
        val loadedBindings = loaded["bindings"] as List<Map<String, Any?>>
        assertEquals("PREDICATE", loadedBindings[0]["memberType"])
        assertEquals(bindings[0]["conditionId"], loadedBindings[0]["conditionId"])

        cleanupAll("verify_deck_1", "t005_pred_inline")
    }

    @Test
    fun `引用已有条件树创建谓词组`() {
        cleanupAll("verify_deck_1", "t005_pred_ref")

        // 先建条件树模板
        val treeResp = call(
            "save_condition_tree",
            """{"name":"t005_spell_template","treeJson":${mapper.writeValueAsString(spellTreeJson)}}"""
        )
        assertFalse(treeResp.isError)
        val treeId = mapper.readValue(treeResp.contentJson, Map::class.java)["id"] as String

        val resp = call(
            "save_card_group",
            """{
              "sourceFile":"verify_deck_1",
              "managerName":"t005_pred_ref",
              "bindings":[{"name":"法术组","conditionId":"$treeId"}]
            }"""
        )
        assertFalse(resp.isError)
        val m = mapper.readValue(resp.contentJson, Map::class.java)
        val bindings = m["bindings"] as List<Map<String, Any?>>

        assertEquals("PREDICATE", bindings[0]["memberType"])
        assertEquals(treeId, bindings[0]["conditionId"])
        // includeDerived 缺省 null（回落卡组级 defaultIncludeDerived）
        assertEquals(null, bindings[0]["includeDerived"])

        cleanupAll("verify_deck_1", "t005_pred_ref")
    }

    @Test
    fun `条件树互斥校验`() {
        cleanupAll("verify_deck_1", "t005_pred_conflict")

        val resp = call(
            "save_card_group",
            """{
              "sourceFile":"verify_deck_1",
              "managerName":"t005_pred_conflict",
              "bindings":[{"name":"冲突组","conditionId":"some_id","conditionTreeJson":${
                mapper.writeValueAsString(
                    spellTreeJson
                )
            }}]
            }"""
        )
        assertTrue("conditionId 与 conditionTreeJson 互斥应报错", resp.isError)

        cleanupAll("verify_deck_1", "t005_pred_conflict")
    }

    @Test
    fun `谓词组跳过卡池校验（cardIds 忽略）`() {
        cleanupAll("verify_deck_1", "t005_pred_nocard")

        // 谓词组同时传了不存在的 cardIds——应被忽略而非报「不在卡池」
        val resp = call(
            "save_card_group",
            """{
              "sourceFile":"verify_deck_1",
              "managerName":"t005_pred_nocard",
              "bindings":[{"name":"法术组","cardIds":["NOT_EXIST_CARD"],"conditionTreeJson":${
                mapper.writeValueAsString(
                    spellTreeJson
                )
            }}]
            }"""
        )
        assertFalse("谓词组应忽略 cardIds 而非报错: ${resp.contentJson}", resp.isError)

        cleanupAll("verify_deck_1", "t005_pred_nocard")
    }

    @Test
    fun `静态组与谓词组同方案共存`() {
        cleanupAll("verify_deck_1", "t005_pred_mix")

        val resp = call(
            "save_card_group",
            """{
              "sourceFile":"verify_deck_1",
              "managerName":"t005_pred_mix",
              "bindings":[
                {"name":"英雄随从","cardIds":["ICC_820","GDB_138"]},
                {"name":"法术组","conditionTreeJson":${mapper.writeValueAsString(spellTreeJson)}}
              ]
            }"""
        )
        assertFalse(resp.isError)
        val m = mapper.readValue(resp.contentJson, Map::class.java)
        val bindings = m["bindings"] as List<Map<String, Any?>>

        assertEquals("STATIC", bindings[0]["memberType"])
        assertEquals(listOf("ICC_820", "GDB_138"), bindings[0]["cardIds"])
        assertEquals("PREDICATE", bindings[1]["memberType"])
        assertEquals(emptyList<String>(), bindings[1]["cardIds"])

        cleanupAll("verify_deck_1", "t005_pred_mix")
    }

    @Test
    fun `卡组级 defaultIncludeDerived 透传`() {
        cleanupAll("verify_deck_1", "t005_pred_default")

        val resp = call(
            "save_card_group",
            """{
              "sourceFile":"verify_deck_1",
              "managerName":"t005_pred_default",
              "defaultIncludeDerived":true,
              "bindings":[{"name":"法术组","conditionTreeJson":${mapper.writeValueAsString(spellTreeJson)}}]
            }"""
        )
        assertFalse(resp.isError)
        val m = mapper.readValue(resp.contentJson, Map::class.java)
        val managerId = m["managerId"] as String

        val getResp = call("get", """{"resource":"card_group","id":"$managerId"}""")
        val loaded = mapper.readValue(getResp.contentJson, Map::class.java)
        assertEquals(true, loaded["defaultIncludeDerived"])

        cleanupAll("verify_deck_1", "t005_pred_default")
    }
}
