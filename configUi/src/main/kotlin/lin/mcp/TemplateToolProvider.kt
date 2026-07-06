package lin.mcp

import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.fasterxml.jackson.databind.ObjectMapper
import lin.ui.db.OrthogonalTemplateEntity
import lin.ui.db.OrthogonalTemplateRepository
import lin.ui.db.TemplateGroupRepository
import lin.utils.nextShortId

/**
 * 正交模板（叶子级）MCP 工具提供者。
 * 仅负责 CONDITION/RULE 两种正交模板的查询与沉淀。
 * 评估树模板（树级）由 AiConfigToolProvider 暴露。
 */
class TemplateToolProvider(
    private val groupRepo: TemplateGroupRepository,
    private val orthogonalRepo: OrthogonalTemplateRepository,
    private val mapper: ObjectMapper
) : McpToolProvider {
    override fun provide(): List<McpToolHandler> = listOf(
        McpToolHandler(
            name = "list_template_groups",
            description = "列出所有模板分组，分组用于将同类模板组织在一起，方便管理和检索。",
            inputSchemaJson = """{"type":"object","properties":{}}""",
            call = {
                McpToolResult(mapper.writeValueAsString(groupRepo.findAll()))
            }
        ),
        typedTool<ListTemplatesInput>(
            name = "list_templates",
            description = "按类型和分组查询正交模板列表。type 为 CONDITION（条件模板）或 RULE（规则模板），不指定时返回全部。",
            mapper = mapper
        ) { input ->
            val types = if (input.type != null) listOf(input.type) else listOf("CONDITION", "RULE")
            val templates = types.flatMap { t ->
                orthogonalRepo.findAllByType(t)
                    .filter { input.groupId == null || it.groupId == input.groupId }
                    .map { it.toSummary(t) }
            }
            McpToolResult(mapper.writeValueAsString(templates))
        },
        typedTool<SaveTemplateInput>(
            name = "save_template",
            description = "将当前的正交条件或规则配置沉淀为可复用模板。当你判断某个条件/规则的组合有复用价值时调用。只需提供结构（引用了哪些数据源和算子类型），不需要保存具体参数值。",
            mapper = mapper
        ) { input ->
            val type = input.type.uppercase()
            if (type !in setOf("CONDITION", "RULE")) {
                return@typedTool McpToolResult(
                    mapper.writeValueAsString(mapOf("error" to "type must be CONDITION or RULE, got: ${input.type}")),
                    isError = true
                )
            }
            val entity = OrthogonalTemplateEntity(
                id = nextShortId(),
                name = input.name,
                description = input.description,
                groupId = input.groupId,
                type = type,
                contentJson = input.contentJson
            )
            orthogonalRepo.save(entity)
            McpToolResult(
                mapper.writeValueAsString(
                    mapOf(
                        "id" to entity.id,
                        "name" to entity.name,
                        "type" to entity.type
                    )
                )
            )
        }
    )

    private fun OrthogonalTemplateEntity.toSummary(type: String): Map<String, Any?> = mapOf(
        "id" to id,
        "name" to name,
        "description" to description,
        "type" to type,
        "groupId" to groupId
    )
}

private data class ListTemplatesInput(
    @field:JsonPropertyDescription("模板类型：CONDITION 或 RULE。不指定则返回全部。")
    val type: String? = null,

    @field:JsonPropertyDescription("模板分组 ID（来自 list_template_groups）。不指定则不按分组过滤。")
    val groupId: String? = null
)

private data class SaveTemplateInput(
    @field:JsonPropertyDescription("模板类型：CONDITION 或 RULE。")
    val type: String,

    @field:JsonPropertyDescription("模板名称")
    val name: String,

    @field:JsonPropertyDescription("模板结构 JSON，只存组件引用（数据源ID、算子ID），不存具体参数值")
    val contentJson: String,

    @field:JsonPropertyDescription("模板描述")
    val description: String? = null,

    @field:JsonPropertyDescription("模板分组 ID（来自 list_template_groups），可选")
    val groupId: String? = null
)
