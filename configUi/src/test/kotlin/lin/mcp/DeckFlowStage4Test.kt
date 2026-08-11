package lin.mcp

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 4：渐进式生成（正交规则 + 正交条件 + 草稿→提交→模板沉淀）。
 *
 * 独立建卡组 → 创草稿树 → put 叶子配置 → commit → save 模板。
 * 验证 AI 组装正交积木链路（DataSource→Transform→Operator）在真实环境中的行为。
 */
class DeckFlowStage4Test : McpTestEnv() {

    private val deckCode =
        "AAEBAZ8FBPfQArjFBdaABtK5Bg3YxwKd7ALZ/gL9uAPruQPTvQTi0wSZjgb1lQbt3waS4Aac6Aaf6AYAAA=="

    @Test
    fun stage4_draftTreeFlow() {
        try {
            // ── 前置：解析卡组 + 建分组（Stage4 独立完成，不依赖其他 Test）──
            call(
                "parse_hearthstone_deck_code",
                """{"deckCode":"$deckCode","groupName":"verify_deck_4","enabled":true}"""
            )
            savedFile = java.nio.file.Path.of("../data/cardgroup/verify_deck_4.cardgroup")

            // 取前 3 张卡做简单分组
            val detailResp = call("get_card_group_detail", """{"fileName":"verify_deck_4"}""")
            val detailData = mapper.readValue(detailResp.contentJson, Map::class.java)

            @Suppress("UNCHECKED_CAST")
            val cards = detailData["cards"] as? List<Map<String, Any?>> ?: emptyList()
            val sampleCardIds = cards.take(3).map { it["cardId"] as String }

            val saveResp = call(
                "save_card_group",
                """{
                  "sourceFile":"verify_deck_4",
                  "managerName":"verify_group_4",
                  "bindings":[{"name":"测试分组","cardIds":${mapper.writeValueAsString(sampleCardIds)}}]
                }"""
            )
            val saveData = mapper.readValue(saveResp.contentJson, Map::class.java)
            managerId = saveData["managerId"] as? String
            @Suppress("UNCHECKED_CAST")
            bindingIds = (saveData["bindingIds"] as? List<*>)?.map { it.toString() } ?: emptyList()
            assertNotNull("managerId", managerId)
            println(">>> managerId=$managerId, bindingIds=$bindingIds")

            // ── Stage 4: 渐进式生成 ──

            // 创草稿树（OrNode 含两个 Leaf：一个正交条件 + 一个正交规则）
            val root =
                """{"OrNode":{"children":[{"Leaf":{"payload":{"Rule":{"nodeId":"leaf1"}}}},{"Leaf":{"payload":{"Rule":{"nodeId":"leaf2"}}}}]}}"""
            val createResp = call(
                "create_draft_tree",
                """{"CreateDraftTree":{
                  "name":"verify_tree_4",
                  "root":$root,
                  "bindingType":"GROUP",
                  "bindingIds":[${bindingIds.joinToString(",") { "\"$it\"" }}],
                  "managerId":"$managerId",
                  "description":"Stage4 端到端验证"
                }}"""
            )
            assertTrue("create_draft_tree should succeed", !createResp.isError)
            val draftId = mapper.readValue(createResp.contentJson, Map::class.java)["draftId"] as String
            println(">>> draftId=$draftId")

            // 填充叶子 1：正交条件（PipelineRef 守卫 — hand_cards → count_projection → gte）
            call(
                "put_draft_leaf",
                """{
                  "draftId":"$draftId",
                  "nodeId":"leaf1",
                  "leafConfig":{
                    "ORTHOGONAL_CONDITION":{
                      "nodeId":"leaf1",
                      "sourceId":"orthogonal_condition",
                      "guardCondition":{"PipelineRef":{"sourceId":"hand_cards","transforms":[{"transformId":"count_projection","args":{}}],"operatorId":"gte","operatorArgs":{"threshold":1},"refId":"leaf1"}},
                      "scoreEffect":{"ConstantScore":{"value":0.0}},
                      "args":{},
                      "guardMissBehavior":"SCORE"
                    }
                  }
                }"""
            )

            // 填充叶子 2：正交规则（SourceScore — hand_cards → identity 打分）
            call(
                "put_draft_leaf",
                """{
                  "draftId":"$draftId",
                  "nodeId":"leaf2",
                  "leafConfig":{
                    "ORTHOGONAL_RULE":{
                      "nodeId":"leaf2",
                      "sourceId":"orthogonal_rule",
                      "scoreEffect":{"SourceScore":{"sourceId":"hand_cards","operatorId":"identity","operatorArgs":{},"missValue":0.0}},
                      "args":{},
                      "guardMissBehavior":"SCORE"
                    }
                  }
                }"""
            )

            // 查草稿状态
            call("get_draft_status", """{"draftId":"$draftId"}""")

            // 提交草稿
            val commitResp = call("commit_draft_tree", """{"draftId":"$draftId"}""")
            assertTrue("commit should succeed", !commitResp.isError)
            treeId = runCatching {
                mapper.readValue(commitResp.contentJson, Map::class.java)["id"] as? String
            }.getOrNull()
            println(">>> committed treeId=$treeId")

            // 沉淀为模板
            val rootLiteral = mapper.writeValueAsString(root)
            call(
                "save_evaluator_tree_template",
                """{"name":"verify_tpl_4","contentJson":$rootLiteral,"description":"Stage4 验证模板"}"""
            )

            assertTrue(true)
        } finally {
            cleanup()
        }
    }
}
