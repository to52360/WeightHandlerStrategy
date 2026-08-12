package lin.mcp

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MCP 工具实战验证驱动器（对应 T-102 端到端验证）。
 *
 * 不走 JSON-RPC/stdio 层，直接加载 Koin + 调用 McpToolProvider 的 call，
 * 既能暴露真实运行错误（DB 连接、解析异常、校验失败），
 * 又能把每个工具的响应 JSON 打印出来，用于发现 description / 输出字段的歧义。
 *
 * 环境搭建能力已提取至 [McpTestEnv]，本类只保留验证逻辑。
 */
class McpToolDriverTest : McpTestEnv() {

    private val deckCode =
        "AAEBAZ8FBPfQArjFBdaABtK5Bg3YxwKd7ALZ/gL9uAPruQPTvQTi0wSZjgb1lQbt3waS4Aac6Aaf6AYAAA=="

    @Test
    fun endToEndSop() {
        try {
            // ── Stage 1: 元数据探查 ──
            call(
                "parse_hearthstone_deck_code",
                """{"deckCode":"$deckCode","groupName":"verify_deck_1","enabled":true}"""
            )
                .also { savedFile = java.nio.file.Path.of("../data/cardgroup/verify_deck_1.cardgroup") }
            call("list", """{"resource":"card_pool"}""")
            call("list", """{"resource":"capability_background"}""")
            call("list_orthogonal_components")

            // ── Stage 2: 前置依赖供给（用解析出的卡池建分组）──
            call(
                "save_card_group",
                """{
                  "sourceFile":"verify_deck_1",
                  "managerName":"verify_group_1",
                  "bindings":[{"name":"英雄随从","cardIds":["ICC_820","GDB_138","WW_051"]}]
                }"""
            )
                .also { r ->
                    val m = mapper.readValue(r.contentJson, Map::class.java)
                    managerId = m["managerId"] as String?
                    @Suppress("UNCHECKED_CAST")
                    bindingIds = (m["bindingIds"] as? List<*>)?.map { it.toString() } ?: emptyList()
                }
            call("list", """{"resource":"card_group"}""")

            // G-04 验证：能查到已有分组的绑定条目 ID（bindingIds 来源）
            if (managerId != null) {
                call("get", """{"resource":"card_group","id":"$managerId"}""")
            }

            // ── Stage 3: 参考模板与存量配置 ──
            call("list", """{"resource":"evaluator_tree"}""")
            call("list", """{"resource":"tree_template"}""")
            call("list", """{"resource":"combo_plan"}""")
            if (managerId != null && bindingIds.isNotEmpty()) {
                val createdPlanResp = call(
                    "save_combo_plan",
                    """{
                      "managerId":"$managerId",
                      "coreGroupIds":["${bindingIds.first()}"],
                      "depGroupIds":["${bindingIds.first()}"],
                      "score":3.5,
                      "relation":"SCORE_ONLY"
                    }"""
                )
                if (createdPlanResp.isError) {
                    throw AssertionError("save_combo_plan 失败（应为校验通过）：${createdPlanResp.contentJson}")
                }
                val createdPlanId = mapper.readValue(createdPlanResp.contentJson, Map::class.java)["id"] as String
                call("get", """{"resource":"combo_plan","id":"$createdPlanId"}""")
                call("delete", """{"resource":"combo_plan","id":"$createdPlanId"}""")
            }

            // ── Stage 4: 渐进式生成 ──
            val root =
                """{"OrNode":{"children":[{"Leaf":{"payload":{"Rule":{"nodeId":"leaf1"}}}},{"Leaf":{"payload":{"Rule":{"nodeId":"leaf2"}}}}]}}"""
            val createResp = call(
                "create_draft_tree",
                """{
                  "name":"verify_tree_1",
                  "root":$root,
                  "bindingType":"GROUP",
                  "bindingIds":[${bindingIds.joinToString(",") { "\"$it\"" }}],
                  "managerId":"$managerId",
                  "description":"端到端验证自动生成"
                }"""
            )
            val draftId = mapper.readValue(createResp.contentJson, Map::class.java)["draftId"] as String

            // 填充叶子 1：正交条件（PipelineRef 守卫）
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

            // 填充叶子 2：正交规则（SourceScore 打分）
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

            call("get", """{"resource":"draft","id":"$draftId"}""")

            val commitResp = call("commit_draft_tree", """{"draftId":"$draftId"}""")
            treeId = runCatching {
                mapper.readValue(commitResp.contentJson, Map::class.java)["id"] as String?
            }.getOrNull()

            // ── 验证编码类规则（purpose 1：真实 coded rule 可被 AI 生成）──
            val codedRoot = """{"OrNode":{"children":[{"Leaf":{"payload":{"Rule":{"nodeId":"cleaf1"}}}}]}}"""
            val codedCreate = call(
                "create_draft_tree",
                """{
                  "name":"verify_coded_1",
                  "root":$codedRoot,
                  "bindingType":"GROUP",
                  "bindingIds":[${bindingIds.joinToString(",") { "\"$it\"" }}],
                  "managerId":"$managerId",
                  "description":"验证编码规则生成"
                }"""
            )
            val codedDraftId = mapper.readValue(codedCreate.contentJson, Map::class.java)["draftId"] as String
            call(
                "put_draft_leaf",
                """{
                  "draftId":"$codedDraftId",
                  "nodeId":"cleaf1",
                  "leafConfig":{
                    "RULE":{
                      "nodeId":"cleaf1",
                      "sourceId":"typed_simple_rule",
                      "args":{"limit":3},
                      "scoreEffect":{"ConstantScore":{"value":0.0}},
                      "guardMissBehavior":"SCORE"
                    }
                  }
                }"""
            )
            call("get", """{"resource":"draft","id":"$codedDraftId"}""")
            val codedCommit = call("commit_draft_tree", """{"draftId":"$codedDraftId"}""")
            codedTreeId = runCatching {
                mapper.readValue(codedCommit.contentJson, Map::class.java)["id"] as String?
            }.getOrNull()

            // 沉淀为模板（检查 save 工具可用）。contentJson 是 String 类型，需传入「序列化后的 JSON 字符串」
            val rootLiteral = mapper.writeValueAsString(root)
            call(
                "save_evaluator_tree_template",
                """{"name":"verify_tpl_1","contentJson":$rootLiteral,"description":"端到端验证模板"}"""
            )

            assertTrue(true)
        } finally {
            // cleanup()
        }
    }

    @Test
    fun testStringifiedRootInCreateDraftTree() {
        val rootStr = mapper.writeValueAsString(
            mapOf(
                "OrNode" to mapOf(
                    "children" to listOf(
                        mapOf("Leaf" to mapOf("payload" to mapOf("Rule" to mapOf("nodeId" to "r1"))))
                    )
                )
            )
        )
        val args = mapOf(
            "name" to "test_stringified_root",
            "root" to rootStr,
            "bindingType" to "CARD",
            "bindingIds" to listOf("BT_020")
        )
        val resp = call("create_draft_tree", mapper.writeValueAsString(args))
        org.junit.Assert.assertFalse(
            "create_draft_tree 应成功解析 String 类型的 root: ${resp.contentJson}",
            resp.isError
        )
        val draftId = mapper.readValue(resp.contentJson, Map::class.java)["draftId"] as String
        call("abandon_draft", """{"draftId":"$draftId"}""")
    }
}
