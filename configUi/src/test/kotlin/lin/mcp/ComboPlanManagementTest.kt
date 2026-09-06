package lin.mcp

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.koin.core.context.GlobalContext
import org.springframework.jdbc.core.JdbcTemplate

class ComboPlanManagementTest : McpTestEnv() {

    private val jdbcTemplate: JdbcTemplate by lazy { GlobalContext.get().get() }

    private val testManagerId = "mgr_mcp_test"
    private val testBindingId1 = "b_mcp_core"
    private val testBindingId2 = "b_mcp_dep"

    @Before
    fun setUpData() {
        jdbcTemplate.update(
            "INSERT INTO card_group_manager (id, name, source_file, enabled) VALUES (?, ?, ?, ?)",
            testManagerId, "MCP测试卡组", "verify_deck_1.cardgroup", 1
        )
        jdbcTemplate.update(
            "INSERT INTO card_group_binding (id, manager_id, name, card_ids) VALUES (?, ?, ?, ?)",
            testBindingId1, testManagerId, "测试核心组", "[\"ICC_820\"]"
        )
        jdbcTemplate.update(
            "INSERT INTO card_group_binding (id, manager_id, name, card_ids) VALUES (?, ?, ?, ?)",
            testBindingId2, testManagerId, "测试依赖组", "[\"GDB_138\"]"
        )
    }

    @After
    fun tearDownData() {
        jdbcTemplate.update("DELETE FROM combo_plan_definition WHERE manager_id = ?", testManagerId)
        jdbcTemplate.update("DELETE FROM tree_config WHERE manager_id = ?", testManagerId)
        jdbcTemplate.update("DELETE FROM card_group_binding WHERE manager_id = ?", testManagerId)
        jdbcTemplate.update("DELETE FROM card_group_manager WHERE id = ?", testManagerId)
    }

    @Test
    fun testSaveComboPlanSuccessAndValidation() {
        // 1. 正常创建 ComboPlan
        val saveResp = call(
            "save_combo_plan",
            """{
              "managerId":"$testManagerId",
              "coreGroupIds":["$testBindingId1"],
              "depGroupIds":["$testBindingId2"],
              "score":4.5,
              "relation":"SCORE_ONLY"
            }"""
        )
        assertFalse("不应返回 error", saveResp.isError)
        assertTrue("响应应包含 saved", saveResp.contentJson.contains("saved"))

        val contentNode = mapper.readTree(saveResp.contentJson)
        val planId = contentNode["id"].asText()
        assertNotNull("自动分配的 id 不能为空", planId)

        // 2. 校验防幻觉：非法 managerId 拒绝
        val invalidMgrResp = call(
            "save_combo_plan",
            """{
              "managerId":"invalid_mgr_xxxx",
              "coreGroupIds":["$testBindingId1"]
            }"""
        )
        assertTrue("非法 managerId 应返回 error", invalidMgrResp.isError)
        assertTrue("应提示卡组不存在并输出可用卡组列表", invalidMgrResp.contentJson.contains("卡组/管理器不存在"))

        // 3. 校验防幻觉：非法 bindingId 拒绝（depGroupIds 提供合法值，以命中"非法分组"分支而非"depGroupIds 为空"分支）
        val invalidBindingResp = call(
            "save_combo_plan",
            """{
              "managerId":"$testManagerId",
              "coreGroupIds":["invalid_binding_id"],
              "depGroupIds":["$testBindingId2"]
            }"""
        )
        assertTrue("非法 bindingId 应返回 error", invalidBindingResp.isError)
        assertTrue("应提示提供的分组 ID 不属于卡组", invalidBindingResp.contentJson.contains("不属于卡组"))
    }

    @Test
    fun testDeleteComboPlanAndUndoRecovery() {
        // 1. 先保存一个 Combo 方案
        val saveResp = call(
            "save_combo_plan",
            """{
              "managerId":"$testManagerId",
              "coreGroupIds":["$testBindingId1"],
              "depGroupIds":["$testBindingId2"],
              "score":5.0,
              "relation":"CORE_BEFORE_DEP"
            }"""
        )
        val planId = mapper.readTree(saveResp.contentJson)["id"].asText()

        // 2. 执行删除，断言返回 snapshotId（T-010：删除前落 delete_snapshot 快照）
        val deleteResp = call("delete", """{"resource":"combo_plan","id":"$planId"}""")
        assertFalse("删除不应返回 error", deleteResp.isError)

        val deleteContent = mapper.readTree(deleteResp.contentJson)
        assertTrue("应当返回 deleted=true", deleteContent["deleted"].asBoolean())
        val snapshotId = deleteContent["snapshotId"]
        assertNotNull("删除应返回 snapshotId", snapshotId)

        // 删除后库中应已无该行
        val afterDelete = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM combo_plan_definition WHERE id = ?", Int::class.java, planId
        )
        assertEquals("删除后库中不应存在", 0, afterDelete)

        // 3. 经 restore_snapshot 一键恢复（原 id 保留）
        val restoreResp = call("restore_snapshot", """{"snapshotId":"${snapshotId.asText()}"}""")
        assertFalse("restore_snapshot 不应报错: ${restoreResp.contentJson}", restoreResp.isError)
        assertTrue("restore 应返回 restored=true", mapper.readTree(restoreResp.contentJson)["restored"].asBoolean())

        // 验证数据库中按原 id 重新存在该行，且内容与删除前一致
        val count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM combo_plan_definition WHERE id = ?", Int::class.java, planId
        )
        assertEquals(1, count)
        val restored = jdbcTemplate.queryForObject(
            "SELECT manager_id, score, relation FROM combo_plan_definition WHERE id = ?",
            { rs, _ -> Triple(rs.getString("manager_id"), rs.getDouble("score"), rs.getString("relation")) },
            planId
        )
        assertEquals(testManagerId, restored.first)
        assertEquals(5.0, restored.second, 0.001)
        assertEquals("CORE_BEFORE_DEP", restored.third)
    }

    @Test
    fun testCoManagerTreesAndComboPlansBiDirectional() {
        // 1. 存入 ComboPlan（必须含 depGroupIds 才构成有效 Combo）
        val saveComboResp = call(
            "save_combo_plan",
            """{
              "managerId":"$testManagerId",
              "coreGroupIds":["$testBindingId1"],
              "depGroupIds":["$testBindingId2"],
              "score":2.0
            }"""
        )
        val comboId = mapper.readTree(saveComboResp.contentJson)["id"].asText()

        // 2. 存入绑定到 testManagerId 的评估树
        val treeId = "tree_mcp_co_test"
        jdbcTemplate.update(
            "INSERT INTO tree_config (id, binding_type, binding_ids, name, description, config_data, enabled, manager_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            treeId, "GROUP", testManagerId, "卡组同源测试树", "desc", "{\"root\":{\"OrNode\":{\"children\":[]}}}", 1, testManagerId
        )

        // 3. 验证 evaluator_tree(action=GET) 能反查到 associatedComboPlans
        val treeGetResp = call("get", """{"resource":"evaluator_tree","id":"$treeId"}""")
        assertFalse("evaluator_tree GET 不应报错", treeGetResp.isError)
        val treeContent = mapper.readTree(treeGetResp.contentJson)

        assertEquals(testManagerId, treeContent["managerId"].asText())
        val associatedComboPlans = treeContent["associatedComboPlans"]
        assertTrue("associatedComboPlans 应非空", associatedComboPlans.isArray && associatedComboPlans.size() > 0)
        assertEquals(comboId, associatedComboPlans[0]["id"].asText())

        // 4. 验证 combo_plan(action=GET) 能反查到 coManagerTrees
        val comboGetResp = call("get", """{"resource":"combo_plan","id":"$comboId"}""")
        assertFalse("combo_plan GET 不应报错", comboGetResp.isError)
        val comboContent = mapper.readTree(comboGetResp.contentJson)

        val coManagerTrees = comboContent["coManagerTrees"]
        assertTrue("coManagerTrees 应包含刚才关联的树", coManagerTrees.isArray && coManagerTrees.size() > 0)
        assertEquals(treeId, coManagerTrees[0]["id"].asText())
    }
}
