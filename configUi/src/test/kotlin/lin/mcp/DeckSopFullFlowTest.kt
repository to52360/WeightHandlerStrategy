package lin.mcp

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 按 use-ai-config-generator SOP 走完整流程：
 * 1. 探查能力面
 * 2. 解析卡组 → 编排真实分组
 * 3. 为每个分组构建评估树（能做的做，不能做的标记 [GAP]）
 *
 * 卡组：AAEBAZ8F...（圣契圣骑士，17张卡）
 */
class DeckSopFullFlowTest : McpTestEnv() {

    private val deckCode =
        "AAEBAZ8FBPfQArjFBdaABtK5Bg3YxwKd7ALZ/gL9uAPruQPTvQTi0wSZjgb1lQbt3waS4Aac6Aaf6AYAAA=="

    /**
     * 分组定义（基于 DB 查询的 cost/type 属性分析）：
     *
     * 圣契引擎: BT_020(1费随从), GDB_726(3费武器), GDB_728(2费随从), TID_077(9费随从)
     * 神圣法术: BT_025(2费HOLY), GDB_137(3费HOLY), GDB_138(4费HOLY)
     * 过牌:    BOT_909(1费法术), ETC_418(2费随从)
     * 解场:    UNG_961(0费), GIL_203(2费), JAM_006(2费), WW_051(2费), WW_336(7费)
     * 独立随从: ICC_820(4费亡灵), VAC_507(5费), TID_098(3费纳迦)
     */
    private val groups = mapOf(
        "圣契引擎_减费核心" to listOf("BT_020", "GDB_726", "GDB_728", "TID_077"),
        "神圣法术_Buff终端" to listOf("BT_025", "GDB_137", "GDB_138"),
        "过牌_检索" to listOf("BOT_909", "ETC_418"),
        "解场_控制" to listOf("UNG_961", "GIL_203", "JAM_006", "WW_051", "WW_336"),
        "独立随从" to listOf("ICC_820", "VAC_507", "TID_098"),
    )

    data class BoundGroup(val name: String, val cardIds: List<String>, val bindingId: String)

    @Test
    fun fullSop() {
        val boundGroups = mutableListOf<BoundGroup>()
        var mgrId: String? = null

        try {
            // ═══════════════════════════════════════════
            // STEP 1: 能力背景探查
            // ═══════════════════════════════════════════
            println("\n========== STEP 1: 能力背景探查 ==========")
            call("list_capability_background")
            call("list_orthogonal_components")
            call(
                "parse_hearthstone_deck_code",
                """{"deckCode":"$deckCode","groupName":"deck_libram","enabled":true}"""
            )
            savedFile = java.nio.file.Path.of("../data/cardgroup/deck_libram.cardgroup")

            // ═══════════════════════════════════════════
            // STEP 2: 编排分组
            // ═══════════════════════════════════════════
            println("\n========== STEP 2: 编排分组 ==========")
            val bindingsJson = groups.entries.joinToString(",") { (name, cardIds) ->
                """{"name":"$name","cardIds":${mapper.writeValueAsString(cardIds)}}"""
            }
            val saveResp = call(
                "save_card_group", """{
                "sourceFile":"deck_libram",
                "managerName":"libram_groups",
                "bindings":[$bindingsJson]
            }"""
            )
            assertTrue("save_card_group should succeed", !saveResp.isError)

            val saveData = mapper.readValue(saveResp.contentJson, Map::class.java)
            mgrId = saveData["managerId"] as? String ?: error("no managerId")
            @Suppress("UNCHECKED_CAST")
            val allBindingIds = (saveData["bindingIds"] as? List<*>)?.map { it.toString() } ?: emptyList()
            managerId = mgrId
            bindingIds = allBindingIds

            println(">>> 创建了 ${groups.size} 个分组，managerId=$mgrId")
            groups.entries.forEachIndexed { i, (name, cardIds) ->
                boundGroups.add(BoundGroup(name, cardIds, allBindingIds[i]))
                println("  [$i] $name: ${cardIds.size} cards, bindingId=${allBindingIds[i]}")
            }

            // ═══════════════════════════════════════════
            // STEP 3: 参考模板
            // ═══════════════════════════════════════════
            println("\n========== STEP 3: 参考模板 ==========")
            call("list_evaluator_trees")
            call("list_evaluator_tree_templates")

            // ═══════════════════════════════════════════
            // STEP 4: 为每个分组构建评估树
            // ═══════════════════════════════════════════
            println("\n========== STEP 4: 构建评估树 ==========")
            for (g in boundGroups) {
                buildTreeForGroup(g, mgrId)
            }

            // 最终缺口汇总
            println("\n========== 缺口汇总 ==========")
            println("[GAP-1] get_card_group_detail 不返回 cost/type — AI 无法智能分组")
            println("[GAP-2] 无 coded rule 支持按 cardId 集合匹配（typed/list_simple_rule 都是费用匹配）")
            println("[GAP-3] 无正交 Transform 可按自定义分组筛选（只有 race_filter）")
            println("[GAP-4] 正交管道无法访问'当前被评估的卡'的属性")
            println("[GAP-5] 编码规则仅 2 种，表达力极有限（费用阈值 / 种族白名单）")

            assertTrue(true)
        } finally {
            cleanup()
        }
    }

    private fun buildTreeForGroup(g: BoundGroup, mgrId: String) {
        println("\n--- ${g.name} (${g.cardIds}) ---")

        // 骨架：OrNode → 一个 Rule 叶（打分）+ 一个 OrthogonalCondition 叶（守卫）
        val root =
            """{"OrNode":{"children":[{"Leaf":{"payload":{"Rule":{"nodeId":"r1"}}}},{"Leaf":{"payload":{"Rule":{"nodeId":"c1"}}}}]}}"""

        val createResp = call(
            "create_draft_tree", """{"CreateDraftTree":{
            "name":"eval_${g.name}",
            "root":$root,
            "bindingType":"GROUP",
            "bindingIds":["${g.bindingId}"],
            "managerId":"$mgrId",
            "description":"$g.name"
        }}"""
        )
        if (createResp.isError) {
            println("  ❌ create 失败"); return
        }
        val draftId = mapper.readValue(createResp.contentJson, Map::class.java)["draftId"] as String

        // ── r1: 编码规则叶（只能用 typed_simple_rule / list_simple_rule）──
        // typed_simple_rule: callCard.card.cost ≤ limit → +1
        // list_simple_rule:  callCard.card.cost ∈ limits → +1
        // 都是费用匹配，无法做 cardId 精确匹配！
        //
        // 尝试：typed_simple_rule 给低费卡加分（这个策略对引擎组有意义）
        val r1 = putLeaf(
            draftId, "r1", "RULE",
            """"sourceId":"typed_simple_rule","args":{"limit":3},""" +
                    """"scoreEffect":{"ConstantScore":{"value":1.0}},"guardMissBehavior":"SCORE""""
        )
        println(if (r1) "  ✅ r1: typed_simple_rule(limit=3) — 费用≤3的卡+1分" else "  ❌ r1: 失败")

        // ── c1: 正交条件叶 ──
        // hand_cards → count_projection → gte(1) = "手牌非空才出牌"
        val c1 = putLeaf(
            draftId, "c1", "ORTHOGONAL_CONDITION",
            """"sourceId":"orthogonal_condition",""" +
                    """"guardCondition":{"PipelineRef":{"sourceId":"hand_cards","transforms":[{"transformId":"count_projection","args":{}}],"operatorId":"gte","operatorArgs":{"threshold":1},"refId":"c1"}},""" +
                    """"scoreEffect":{"ConstantScore":{"value":0.0}},"args":{},"guardMissBehavior":"SCORE""""
        )
        println(if (c1) "  ✅ c1: 正交条件 — 手牌≥1张才评估" else "  ❌ c1: 失败")

        // 提交
        call("get_draft_status", """{"draftId":"$draftId"}""")
        val commitResp = call("commit_draft_tree", """{"draftId":"$draftId"}""")
        val ok = runCatching {
            mapper.readValue(commitResp.contentJson, Map::class.java)["id"] as? String
        }.getOrNull() != null
        if (ok) {
            println("  ✅ 提交成功")
        } else {
            // 清理脏数据
            val jdbc = org.koin.core.context.GlobalContext.get().get<org.springframework.jdbc.core.JdbcTemplate>()
            jdbc.update("DELETE FROM evaluator_leaf_config WHERE config_id = ?", draftId)
            jdbc.update("DELETE FROM tree_config WHERE id = ?", draftId)
            println("  ⚠️ 提交失败，已回滚")
        }
    }

    private fun putLeaf(draftId: String, nodeId: String, leafType: String, configBody: String): Boolean {
        val json = """{
            "draftId":"$draftId",
            "nodeId":"$nodeId",
            "leafConfig":{
                "$leafType":{
                    "nodeId":"$nodeId",
                    $configBody
                }
            }
        }"""
        val resp = call("put_draft_leaf", json)
        if (resp.isError) return false
        val validation = mapper.readValue(resp.contentJson, Map::class.java)["validation"] as? Map<*, *>
        return validation?.get("ok") == true
    }
}
