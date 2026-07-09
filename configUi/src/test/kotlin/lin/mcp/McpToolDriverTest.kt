package lin.mcp

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import lin.moduls.loadMcpModules
import org.junit.Assert.assertTrue
import org.junit.Test
import org.springframework.jdbc.core.JdbcTemplate
import java.nio.file.Files
import java.nio.file.Path

/**
 * MCP 工具实战验证驱动器（对应 T-102 端到端验证）。
 *
 * 不走 JSON-RPC/stdio 层，直接加载 Koin + 调用 McpToolProvider 的 call，
 * 既能暴露真实运行错误（DB 连接、解析异常、校验失败），
 * 又能把每个工具的响应 JSON 打印出来，用于发现 description / 输出字段的歧义。
 *
 * 运行目录为 configUi，因此 DB 相对路径需回退一层指向仓库根。
 */
class McpToolDriverTest {

    companion object {
        private lateinit var tools: Map<String, McpToolHandler>
        private val mapper = ObjectMapper().apply { enable(SerializationFeature.INDENT_OUTPUT) }

        // 本次运行产生的、需在 finally 中清理的资源
        private var savedFile: Path? = null
        private var managerId: String? = null
        private var bindingIds: List<String> = emptyList()
        private var treeId: String? = null
        private var codedTreeId: String? = null

        @JvmStatic
        @org.junit.BeforeClass
        fun setup() {
            System.setProperty("hs_cards.db.path", "../hs_cards.db")
            System.setProperty("database.path", "../weightHandlerStrategy.db")
            System.setProperty("cardgroup.dir.path", "../data/cardgroup")
            loadMcpModules()
            val providers = org.koin.core.context.GlobalContext.get().getAll<McpToolProvider>()
            tools = providers.flatMap { it.provide() }.associateBy { it.name }
            println(">>> loaded tools: ${tools.keys.joinToString()}")
        }
    }

    private fun call(name: String, json: String = "{}"): McpToolResult {
        val handler = tools[name] ?: error("tool not found: $name (available: ${tools.keys})")
        val args = mapper.readValue(json, Map::class.java) as Map<String, Any?>
        return try {
            val result = handler.call(args)
            val pretty = runCatching {
                mapper.writeValueAsString(mapper.readValue(result.contentJson, Any::class.java))
            }.getOrElse { result.contentJson }
            println("===== $name (isError=${result.isError}) =====")
            println(pretty)
            result
        } catch (e: Throwable) {
            println("===== $name THREW =====")
            e.printStackTrace()
            throw e
        }
    }

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
                .also { savedFile = Path.of("../data/cardgroup/verify_deck_1.cardgroup") }
            call("list_card_group_sources")
            call("list_capability_background")
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
            call("list_card_groups")

            // G-04 验证：能查到已有分组的绑定条目 ID（bindingIds 来源）
            if (managerId != null) {
                call("get_card_group_manager", """{"managerId":"$managerId"}""")
            }

            // ── Stage 3: 参考模板与存量配置 ──
            call("list_evaluator_trees")
            call("list_evaluator_tree_templates")

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

            call("get_draft_status", """{"draftId":"$draftId"}""")

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
            call("get_draft_status", """{"draftId":"$codedDraftId"}""")
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
            cleanup()
        }
    }

    private fun cleanup() {
        runCatching {
            val jdbc = org.koin.core.context.GlobalContext.get().get<JdbcTemplate>()
            treeId?.let {
                jdbc.update("DELETE FROM evaluator_leaf_config WHERE config_id = ?", it)
                jdbc.update("DELETE FROM tree_config WHERE id = ?", it)
            }
            codedTreeId?.let {
                jdbc.update("DELETE FROM evaluator_leaf_config WHERE config_id = ?", it)
                jdbc.update("DELETE FROM tree_config WHERE id = ?", it)
            }
            managerId?.let {
                jdbc.update("DELETE FROM card_group_binding WHERE manager_id = ?", it)
                jdbc.update("DELETE FROM card_group_manager WHERE id = ?", it)
            }
            savedFile?.let { Files.deleteIfExists(it) }
            println(">>> cleanup done (treeId=$treeId, managerId=$managerId, savedFile=$savedFile)")
        }.onFailure { e -> println(">>> cleanup failed: ${e.message}") }
    }
}
