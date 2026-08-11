package lin.mcp

import org.junit.Test

/**
 * 真实演练 Stage 3:
 * 纯 MCP 工具链：先探查可用能力 → 再逐组建树。
 * 全程不绕过 MCP 接口，不预设分组内部结构。
 *
 * ## 目标
 * 1. 通过 MCP 工具获取刚创建的分组，展示名称+ID
 * 2. 探查当前规则池，确认为每组能分配什么规则
 * 3. 逐一建树，暴露真实缺口
 */
class DeckFlowStage3_TreeTest : McpTestEnv() {

    companion object {
        const val FILE_NAME = "real_libram_deck"
        const val MANAGER_NAME = "real_libram_groups"
    }

    @Test
    fun createTrees() {
        println("========== Stage 3: 纯 MCP 工具链建树 ==========")

        // ── 步骤 1: 探查当前规则池（纯 MCP） ──
        println("\n--- 步骤 1: 探查可用能力 ---")
        call("list_capability_background")

        // ── 步骤 2: 通过 MCP 找到分组（名称+ID） ──
        println("\n--- 步骤 2: 查找分组 ---")
        val listResp = call("card_group", """{"action":"LIST"}""")
        @Suppress("UNCHECKED_CAST")
        val groups = mapper.readValue(listResp.contentJson, List::class.java) as List<Map<String, Any>>
        val target = groups.find { it["name"] == MANAGER_NAME }
            ?: error("没找到 '$MANAGER_NAME' — 请先执行 Stage 2")
        managerId = target["id"] as String
        println(">>> 找到 Manager: name='${target["name"]}' id=$managerId")

        // ── 步骤 3: 通过 MCP 获取所有 binding（名称+ID+cardIds） ──
        println("\n--- 步骤 3: 获取分组详情 ---")
        val mgrResp = call("card_group", """{"action":"GET","managerId":"$managerId"}""")
        val mgrData = mapper.readValue(mgrResp.contentJson, Map::class.java)
        @Suppress("UNCHECKED_CAST")
        val bindings = mgrData["bindings"] as List<Map<String, Any>>

        println(">>> 共 ${bindings.size} 个分组:")
        for (b in bindings) {
            println("  name='${b["name"]}' id=${b["id"]} cardIds=${b["cardIds"]}")
        }

        // ── 步骤 4: 探查正交积木（细粒度，纯 MCP） ──
        println("\n--- 步骤 4: 探查正交积木 ---")
        call("list_orthogonal_components")

        // ── 步骤 5: 逐组建树（纯 MCP 草稿流程） ──
        println("\n--- 步骤 5: 逐组建树 ---")
        val results = mutableListOf<TreeResult>()

        for ((idx, binding) in bindings.withIndex()) {
            val bindingId = binding["id"] as String
            val bindingName = binding["name"] as String
            @Suppress("UNCHECKED_CAST")
            val cardIds = binding["cardIds"] as List<String>

            println("\n========== 建树[$idx]: name='$bindingName' id=$bindingId ==========")
            println("  cardIds: $cardIds")

            val result = createTreeViaMcp(bindingId, bindingName, cardIds)
            results.add(result)

            if (result.error != null) {
                println("  ⚠ 失败原因: ${result.error}")
            }
        }

        // ── 步骤 6: 汇总报告 ──
        println("\n========================================")
        println("========== Stage 3 汇总 ==========")
        println("Manager: name='$MANAGER_NAME' id=$managerId")
        println("分组总数: ${bindings.size} | 成功: ${results.count { it.committed }} | 失败: ${results.count { !it.committed }}")
        println()
        println("明细:")
        for (r in results) {
            val status = when {
                r.committed -> "✅"
                r.draftId.isNotEmpty() -> "⏳(草稿=${r.draftId})"
                else -> "❌"
            }
            println("  $status name='${r.name}' draftId='${r.draftId}' treeId='${r.treeId ?: "N/A"}'")
        }
        if (results.any { it.error != null }) {
            println("\n失败原因:")
            results.filter { it.error != null }.forEach {
                println("  name='${it.name}': ${it.error}")
            }
        }

        // 记录所有 treeId
        allTreeIds.addAll(results.mapNotNull { it.treeId })

        // 确认：列出所有已保存的树
        println("\n--- 当前所有评估树 ---")
        call("evaluator_tree", """{"action":"LIST"}""")

        // 保存追踪
        saveTrackedIds(FILE_NAME, MANAGER_NAME, managerId, allTreeIds)
    }

    data class TreeResult(
        val name: String,
        val draftId: String = "",
        val treeId: String? = null,
        val committed: Boolean = false,
        val error: String? = null
    )

    /**
     * 纯 MCP 工具链创建一棵树:
     * create_draft_tree → put_draft_leaf → commit_draft_tree
     *
     * @param bindingName 分组名（用于展示）
     * @param bindingId   绑定 ID
     * @param cardIds     该组包含的 cardId 列表（AI 据此选择 Rule 参数）
     */
    private fun createTreeViaMcp(bindingId: String, bindingName: String, cardIds: List<String>): TreeResult {
        // step A: 创建草稿骨架
        val root = """{"Leaf":{"payload":{"Rule":{"nodeId":"r1"}}}}"""
        val createResp = call(
            "create_draft_tree", """{"CreateDraftTree":{
            "name":"tree_$bindingName",
            "root":$root,
            "bindingType":"GROUP",
            "bindingIds":["$bindingId"],
            "managerId":"$managerId",
            "description":"[$bindingName] 评估树"
        }}"""
        )
        if (createResp.isError) {
            return TreeResult(bindingName, error = "create_draft_tree: ${createResp.contentJson}")
        }
        val draftId = mapper.readValue(createResp.contentJson, Map::class.java)["draftId"] as String
        println("  draftId='$draftId'")

        // step B: 填充叶子 — AI 根据分组名和 cardIds 选择 Rule
        // 当前规则池只有 typed_simple_rule(按费用≤limit) 可用
        // AI 的决策逻辑：
        //   - 读取 bindingName 推断策略角色
        //   - 读取 cardIds → 需要查卡牌 cost？但纯 MCP 工具链没有单卡查询工具！
        //     → 这是真实缺口：AI 无法通过 MCP 查询单卡费用
        //   - 退而求其次：根据分组命名猜测合适的 limit
        val leafConfig = buildLeafConfig(bindingName, cardIds)

        val putResp = call(
            "put_draft_leaf",
            """{
              "draftId":"$draftId",
              "nodeId":"r1",
              "leafConfig":$leafConfig
            }"""
        )
        if (putResp.isError) {
            return TreeResult(bindingName, draftId = draftId, error = "put_draft_leaf: ${putResp.contentJson}")
        }

        // step C: 提交
        val commitResp = call("commit_draft_tree", """{"draftId":"$draftId"}""")
        return if (commitResp.isError) {
            TreeResult(bindingName, draftId = draftId, error = "commit: ${commitResp.contentJson}")
        } else {
            val treeId = mapper.readValue(commitResp.contentJson, Map::class.java)["id"] as? String
            println("  ✅ treeId='$treeId'")
            TreeResult(bindingName, draftId = draftId, treeId = treeId, committed = true)
        }
    }

    /**
     * AI 根据分组策略角色选择 Rule 参数。
     *
     * ⚠ 当前限制：纯 MCP 工具链没有"查询单卡费用/类型"的工具。
     * AI 只能根据分组命名 + cardIds 列表做粗粒度推断。
     * 这正是 G-07/G-08 要暴露的缺口。
     */
    private fun buildLeafConfig(bindingName: String, cardIds: List<String>): String {
        // 当前规则池仅 typed_simple_rule(limit=N) 可用
        // AI 根据组名推断合理的 limit 参数
        val limit = when {
            bindingName.contains("引擎") || bindingName.contains("减费") -> 3
            bindingName.contains("法术") -> 4
            bindingName.contains("过牌") || bindingName.contains("检索") -> 3
            bindingName.contains("干扰") || bindingName.contains("拖延") -> 3
            bindingName.contains("解场") || bindingName.contains("清理") -> 7
            bindingName.contains("终端") || bindingName.contains("制胜") -> 9
            else -> 3
        }
        println("  AI决策: '$bindingName' → typed_simple_rule(limit=$limit)")

        return """{
            "RULE":{
              "nodeId":"r1",
              "sourceId":"typed_simple_rule",
              "args":{"limit":$limit},
              "scoreEffect":{"ConstantScore":{"value":1.0}},
              "guardMissBehavior":"SCORE"
            }
        }"""
    }
}
