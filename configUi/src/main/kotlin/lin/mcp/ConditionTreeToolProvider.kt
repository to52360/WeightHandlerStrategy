package lin.mcp

import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.fasterxml.jackson.databind.ObjectMapper
import lin.repository.condition_tree.ConditionTreeConfigService
import lin.repository.condition_tree.createConditionTreeConfigMapper
import lin.rule.condition.ConditionTreeConfig

/**
 * 消费方条件树引用解析（Q-003 内联创建）：提供 treeJson 时自动创建条件树并返回新 id；
 * 否则按 conditionId 引用已有条件树（校验存在）。
 * 一次性树无需先 condition_tree(action=SAVE) 建模板，直接在消费方内联 JSON 一步创建。
 * @param managerId 消费方归属卡组：内联创建时写入树归属（null/空 = 全局共享树）。
 */
fun resolveConditionTreeReference(
    service: ConditionTreeConfigService,
    mapper: ObjectMapper,
    conditionId: String?,
    treeJson: String?,
    defaultName: String,
    label: String,
    managerId: String? = null
): String {
    val hasId = !conditionId.isNullOrBlank()
    val hasJson = !treeJson.isNullOrBlank()
    if (hasId && hasJson) {
        throw McpBadInput("$label：conditionId 与 treeJson 互斥，只能提供其一（复用已有树传 conditionId，一次性树传 treeJson 内联创建）")
    }
    if (hasJson) {
        val config = try {
            mapper.readValue(treeJson, ConditionTreeConfig::class.java)
        } catch (e: Exception) {
            throw McpBadInput(
                "$label 内联条件树 JSON 解析失败（需完整 {id,name,root} 结构，多态节点名用 AndNode/OrNode/NotNode/BranchNode/Leaf）: ${e.message}"
            )
        }
        return service.saveConfig(config.name ?: defaultName, config, managerId = managerId, inlineCreated = true)
    }
    if (!hasId) {
        throw McpBadInput("$label：conditionId 与 treeJson 必须提供其一（一次性树传 treeJson 内联创建，无需先建模板）")
    }
    if (service.findById(conditionId) == null) {
        throw McpBadInput("$label 条件树不存在: $conditionId（一次性树可直接传 treeJson 内联创建，或先 condition_tree(action=SAVE) 建模板）")
    }
    return conditionId!!
}

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
                查询条件树（全局逻辑资源，排序/评分/push 广播共用）。
                action=LIST：列出条件树元数据（id/name/managerId/inlineCreated），可传 managerId 按"当前卡组私有 + 全局共享"过滤；
                不传 managerId 则全量返回。inlineCreated=true 表示消费方内联自动创建的一次性树。
                action=GET：读取单棵完整配置 JSON（root 为 AndNode/OrNode/NotNode/BranchNode/Leaf 节点对象，
                叶子 payload 为 ConditionRef（引用编码条件，conditionId+args）或 PipelineRef（正交管道
                sourceId + transforms + operatorId + operatorArgs + refId））。
            """.trimIndent()
        ) { input ->
            when (val query = input.toQuery()) {
                is ConditionTreeQuery.List -> mcpSuccess(
                    service.loadAllMeta(query.managerId).map {
                        mapOf(
                            "id" to it.id,
                            "name" to it.name,
                            "managerId" to it.managerId,
                            "inlineCreated" to it.inlineCreated
                        )
                    }
                )

                is ConditionTreeQuery.Get -> {
                    val entity = service.loadAll().firstOrNull { it.first.id == query.id }
                        ?: return@typedTool mcpError("条件树不存在: ${query.id}")
                    val config = entity.second
                        ?: return@typedTool mcpError("条件树配置解析失败: ${query.id}")
                    mcpSuccess(
                        mapOf(
                            "id" to config.id,
                            "name" to config.name,
                            "managerId" to entity.first.managerId,
                            "inlineCreated" to entity.first.inlineCreated,
                            "configData" to mapper.writeValueAsString(config)
                        )
                    )
                }
            }
        },

        typedTool<SaveConditionTreeInput>(
            name = "save_condition_tree",
            description = """
                创建或更新一棵条件树（组合逻辑模板），**仅一次性树需要**（或需在多消费方间复用时建模板）：
                消费方（save_aura_boost 的 conditionTreeJson、save_card_group 的 conditionalStageConditionTreeJson）
                已支持直接内联 treeJson 一步创建，无需先调本工具。
                treeJson 传「完整条件树 JSON 的字符串」：{id, name, root}，root 为节点对象。
                叶子 payload 两种：ConditionRef（引用编码条件，conditionId + args）或
                PipelineRef（正交管道：sourceId + transforms + operatorId + operatorArgs + refId）。
                条件树参数（ConditionRef.args / PipelineRef.operatorArgs / transform 参数）直接存树内，
                GET 读取原样返回；评估树引用时由叶子 args 决定参数（树内参数仅作表单参考）。
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
            val id = service.saveConfig(input.name, config, input.existingId, managerId = input.managerId)
            mcpSuccess(mapOf("id" to id, "name" to input.name, "managerId" to input.managerId))
        }
    )
}

private sealed interface ConditionTreeQuery {
    data class List(val managerId: String?) : ConditionTreeQuery
    data class Get(val id: String) : ConditionTreeQuery
}

private data class ConditionTreeQueryInput(
    @field:JsonPropertyDescription("操作类型：LIST 列出条件树元数据（可传 managerId 过滤），GET 读取单棵完整配置。")
    val action: String,
    @field:JsonPropertyDescription("条件树 ID（8 位短 id），仅 action=GET 时必填。")
    val id: String? = null,
    @field:JsonPropertyDescription("可选：仅 action=LIST 时有效，按卡组 managerId 过滤（返回当前卡组私有 + 全局共享树）。不传则全量返回。")
    val managerId: String? = null
) {
    fun toQuery(): ConditionTreeQuery = when (action.uppercase()) {
        "LIST" -> ConditionTreeQuery.List(managerId)
        "GET" -> ConditionTreeQuery.Get(id ?: throw McpBadInput("action=GET 必须提供 id"))
        else -> throw McpBadInput("未知 action: $action，支持 LIST / GET")
    }
}

private data class SaveConditionTreeInput(
    @field:JsonPropertyDescription("条件树名称（用途命名建议：sort_/boost_ 前缀区分排序/push 用途）")
    val name: String,
    @field:JsonPropertyDescription("完整条件树 JSON（字符串类型）：{id, name, root}。必须传「序列化后的 JSON 文本」作为整体字符串，不要传嵌套对象。")
    val treeJson: String,
    @field:JsonPropertyDescription("可选：归属卡组 managerId（来自 card_group(action=LIST)）。null/不传 = 全局共享树。")
    val managerId: String? = null,
    @field:JsonPropertyDescription("可选：更新已有条件树时传其 id；不传则新建。")
    val existingId: String? = null
)
