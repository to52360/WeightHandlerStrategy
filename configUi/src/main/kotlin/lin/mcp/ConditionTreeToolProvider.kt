package lin.mcp

import com.fasterxml.jackson.annotation.JsonPropertyDescription
import lin.repository.condition_tree.ConditionTreeConfigService
import lin.repository.condition_tree.createConditionTreeConfigMapper
import lin.rule.condition.ConditionTreeConfig

/**
 * 条件树（全局逻辑资源）MCP 工具提供者。
 *
 * 条件树是组合逻辑模板（And/Or/Not/Branch + 叶子 ConditionRef/PipelineRef），
 * 被评估树叶子（ConditionTreeLeafConfig）、排序 conditionalStage、push AuraBoost 三类消费方共用。
 * 按 D-003 条件树为全局资源：LIST 全量返回，不按卡组过滤。
 */
class ConditionTreeToolProvider(
    private val service: ConditionTreeConfigService
) : McpToolProvider {

    private val mapper = createConditionTreeConfigMapper()

    override fun provide(): List<McpToolHandler> = listOf(
        typedTool<ConditionTreeQueryInput>(
            name = "condition_tree",
            description = """
                查询条件树（全局逻辑资源，排序/评分/push 广播共用，不按卡组过滤）。
                支持 action=LIST（列出全部 id/name 元数据）和 action=GET（读取单棵完整配置 JSON：
                root 为 AndNode/OrNode/NotNode/BranchNode/Leaf 节点对象，叶子 payload 为
                ConditionRef（引用编码条件，conditionId+args）或 PipelineRef（正交管道
                sourceId + transforms + operatorId + operatorArgs + refId））。
            """.trimIndent()
        ) { input ->
            when (val query = input.toQuery()) {
                is ConditionTreeQuery.List -> mcpSuccess(
                    service.loadAllMeta().map { mapOf("id" to it.first, "name" to it.second) }
                )

                is ConditionTreeQuery.Get -> {
                    val config = service.findById(query.id)
                        ?: return@typedTool mcpError("条件树不存在: ${query.id}")
                    mcpSuccess(
                        mapOf(
                            "id" to config.id,
                            "name" to config.name,
                            "configData" to mapper.writeValueAsString(config)
                        )
                    )
                }
            }
        },

        typedTool<SaveConditionTreeInput>(
            name = "save_condition_tree",
            description = """
                创建或更新一棵条件树（组合逻辑模板）。
                treeJson 传「完整条件树 JSON 的字符串」：{id, name, root}，root 为节点对象。
                叶子 payload 两种：ConditionRef（引用编码条件，conditionId + args）或
                PipelineRef（正交管道：sourceId + transforms + operatorId + operatorArgs + refId）。
                可从 condition_tree(action=GET) 拿现有树复制修改后回传；管道积木用 list_orthogonal_components 查询。
                提供 existingId 更新已有树，否则新建（自动生成 8 位短 id）。
            """.trimIndent()
        ) { input ->
            val config = try {
                mapper.readValue(input.treeJson, ConditionTreeConfig::class.java)
            } catch (e: Exception) {
                return@typedTool mcpError(
                    "treeJson 解析失败（需完整 {id,name,root} 结构，多态节点名用 AndNode/OrNode/NotNode/BranchNode/Leaf）: ${e.message}"
                )
            }
            val id = service.saveConfig(input.name, config, input.existingId)
            mcpSuccess(mapOf("id" to id, "name" to input.name))
        }
    )
}

private sealed interface ConditionTreeQuery {
    data object List : ConditionTreeQuery
    data class Get(val id: String) : ConditionTreeQuery
}

private data class ConditionTreeQueryInput(
    @field:JsonPropertyDescription("操作类型：LIST 列出全部条件树元数据，GET 读取单棵完整配置。")
    val action: String,
    @field:JsonPropertyDescription("条件树 ID（8 位短 id），仅 action=GET 时必填。")
    val id: String? = null
) {
    fun toQuery(): ConditionTreeQuery = when (action.uppercase()) {
        "LIST" -> ConditionTreeQuery.List
        "GET" -> ConditionTreeQuery.Get(id ?: throw McpBadInput("action=GET 必须提供 id"))
        else -> throw McpBadInput("未知 action: $action，支持 LIST / GET")
    }
}

private data class SaveConditionTreeInput(
    @field:JsonPropertyDescription("条件树名称（用途命名建议：sort_/boost_ 前缀区分排序/push 用途）")
    val name: String,
    @field:JsonPropertyDescription("完整条件树 JSON（字符串类型）：{id, name, root}。必须传「序列化后的 JSON 文本」作为整体字符串，不要传嵌套对象。")
    val treeJson: String,
    @field:JsonPropertyDescription("可选：更新已有条件树时传其 id；不传则新建。")
    val existingId: String? = null
)
