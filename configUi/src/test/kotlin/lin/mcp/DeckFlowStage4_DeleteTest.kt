package lin.mcp

import org.junit.Assert.*
import org.junit.Test

/**
 * 验证 T-118: delete_card_group + delete_evaluator_tree MCP 工具。
 *
 * 纯 MCP 工具链测试，不绕过任何内部方法。
 */
class DeckFlowStage4_DeleteTest : McpTestEnv() {

    companion object {
        const val FILE_NAME = "real_libram_deck"
        const val MANAGER_NAME = "real_libram_groups"
    }

    @Test
    fun testDeleteTools() {
        // ── 0. 确认 tool 已注册 ──
        assertTrue("delete_card_group tool 未注册", "delete_card_group" in tools)
        assertTrue("delete_evaluator_tree tool 未注册", "delete_evaluator_tree" in tools)
        println("✅ delete 工具均已注册")

        // ── 1. 找到当前 manager ──
        val listResp = call("card_group", """{"action":"LIST"}""")
        @Suppress("UNCHECKED_CAST")
        val groups = mapper.readValue(listResp.contentJson, List::class.java) as List<Map<String, Any>>
        val target = groups.find { it["name"] == MANAGER_NAME }
            ?: error("没找到 '$MANAGER_NAME'，请先执行 Stage 2")

        val managerId = target["id"] as String
        println(">>> 当前 Manager: name='${target["name"]}' id=$managerId")

        // ── 2. 获取关联的树 ──
        val treeListResp = call("evaluator_tree", """{"action":"LIST"}""")
        @Suppress("UNCHECKED_CAST")
        val trees = mapper.readValue(treeListResp.contentJson, List::class.java) as List<Map<String, Any>>
        val linkedTrees = trees.filter { it["managerId"] == managerId }
        println(">>> 关联评估树: ${linkedTrees.size} 棵")
        linkedTrees.forEach { println("  name='${it["name"]}' id=${it["id"]}") }

        // ── 3. 测试 delete_evaluator_tree（删一棵树） ──
        if (linkedTrees.isNotEmpty()) {
            val firstTree = linkedTrees.first()
            val firstTreeId = firstTree["id"] as String
            val firstName = firstTree["name"] as String

            println("\n--- 测试 delete_evaluator_tree ---")
            val delTreeResp = call("delete_evaluator_tree", """{"treeId":"$firstTreeId"}""")
            assertFalse("delete_evaluator_tree 不应报错", delTreeResp.isError)
            val delTreeData = mapper.readValue(delTreeResp.contentJson, Map::class.java)
            assertTrue("deleted 应为 true", delTreeData["deleted"] == true)
            assertEquals("treeId 应匹配", firstTreeId, delTreeData["treeId"])
            assertEquals("treeName 应匹配", firstName, delTreeData["treeName"])
            println("✅ 成功删除树: name='${delTreeData["treeName"]}' id=${delTreeData["treeId"]}")

            // 验证已删
            val afterTreeList = call("evaluator_tree", """{"action":"LIST"}""")
            @Suppress("UNCHECKED_CAST")
            val afterTrees = mapper.readValue(afterTreeList.contentJson, List::class.java) as List<Map<String, Any>>
            assertFalse("树应已不在列表中", afterTrees.any { it["id"] == firstTreeId })
            println("✅ 确认树已不在列表中")

            // 测试无效 ID
            println("\n--- 测试 delete_evaluator_tree 无效 ID ---")
            val invalidResp = call("delete_evaluator_tree", """{"treeId":"nonexistent_id_xyz"}""")
            assertTrue("无效 ID 应报错", invalidResp.isError)
            val invalidData = mapper.readValue(invalidResp.contentJson, Map::class.java)
            assertTrue("应有 error 字段", invalidData.containsKey("error"))
            assertTrue("应有 existingTrees 列表", invalidData.containsKey("existingTrees"))
            println("✅ 无效 ID 正确返回错误 + 现有树列表")
        }

        // ── 4. 测试 delete_card_group（删除全部） ──
        println("\n--- 测试 delete_card_group ---")
        val delMgrResp = call("delete_card_group", """{"managerId":"$managerId"}""")
        assertFalse("delete_card_group 不应报错", delMgrResp.isError)
        val delMgrData = mapper.readValue(delMgrResp.contentJson, Map::class.java)
        assertTrue("deleted 应为 true", delMgrData["deleted"] == true)
        assertEquals("managerId 应匹配", managerId, delMgrData["managerId"])
        assertNotNull("deletedBindings 不应为空", delMgrData["deletedBindings"])
        assertNotNull("deletedTrees 不应为空", delMgrData["deletedTrees"])
        println("✅ Manager 已删除:")
        println("  managerName: ${delMgrData["managerName"]}")
        println("  deletedBindings: ${delMgrData["deletedBindings"]}")
        println("  deletedTrees: ${delMgrData["deletedTrees"]}")
        println("  totalDeleted: ${delMgrData["totalDeleted"]}")

        // 验证 manager 已不在
        val afterMgrList = call("card_group", """{"action":"LIST"}""")
        @Suppress("UNCHECKED_CAST")
        val afterMgrs = mapper.readValue(afterMgrList.contentJson, List::class.java) as List<Map<String, Any>>
        assertFalse("Manager 应已不在列表中", afterMgrs.any { it["id"] == managerId })
        println("✅ 确认 Manager 已不在列表中")

        // 验证关联树也已级联删除
        val finalTreeList = call("evaluator_tree", """{"action":"LIST"}""")
        @Suppress("UNCHECKED_CAST")
        val finalTrees = mapper.readValue(finalTreeList.contentJson, List::class.java) as List<Map<String, Any>>
        val remainingLinked = finalTrees.filter { it["managerId"] == managerId }
        assertEquals("关联树应全部级联删除", 0, remainingLinked.size)
        println("✅ 确认关联树已全部级联删除")

        println("\n========== T-118 delete 工具验证通过 ==========")
    }
}
