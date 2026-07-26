package lin.mcp

import com.fasterxml.jackson.annotation.JsonPropertyDescription
import lin.repository.OrthogonalTemplateEntity
import lin.repository.OrthogonalTemplateRepository
import lin.repository.TemplateGroupRepository
import lin.utils.nextShortId

/**
 * 正交模板（叶子级）MCP 工具提供者。
 * 仅负责 CONDITION/RULE 两种正交模板的查询与沉淀。
 */
class TemplateToolProvider(
    private val groupRepo: TemplateGroupRepository,
    private val orthogonalRepo: OrthogonalTemplateRepository
) : McpToolProvider {
    override fun provide(): List<McpToolHandler> = listOf(
        // ── template_browse: 模板分组 + 模板列表 (合并) ──
        typedTool<TemplateBrowseInput>(
            name = "template_browse",
            description = "浏览正交模板。支持 action=GROUPS（列出所有模板分组）和 action=TEMPLATES（按类型/分组查询模板列表，type 为 CONDITION 或 RULE，不指定返回全部）。"
        ) { input ->
            when (val query = input.toQuery()) {
                is TemplateBrowseQuery.Groups -> mcpSuccess(groupRepo.findAll())
                is TemplateBrowseQuery.Templates -> {
                    val types = if (query.type != null) listOf(query.type) else listOf("CONDITION", "RULE")
                    val templates = types.flatMap { t ->
                        orthogonalRepo.findAllByType(t)
                            .filter { query.groupId == null || it.groupId == query.groupId }
                            .map { it.toSummary(t) }
                    }
                    mcpSuccess(templates)
                }
            }
        },

        // ── save_template (保留) ──
        typedTool<SaveTemplateInput>(
            name = "save_template",
            description = "将当前的正交条件或规则配置沉淀为可复用模板。type 为 CONDITION 或 RULE。只需提供结构（引用了哪些数据源和算子类型），不需要保存具体参数值。"
        ) { input ->
            val type = input.type.uppercase()
            if (type !in setOf("CONDITION", "RULE")) {
                return@typedTool mcpError("type must be CONDITION or RULE, got: ${input.type}")
            }
            val entity = OrthogonalTemplateEntity(id = nextShortId(), name = input.name, description = input.description, groupId = input.groupId, type = type, contentJson = input.contentJson)
            orthogonalRepo.save(entity)
            mcpSuccess(mapOf("id" to entity.id, "name" to entity.name, "type" to entity.type))
        }
    )

    private fun OrthogonalTemplateEntity.toSummary(type: String): Map<String, Any?> = mapOf(
        "id" to id, "name" to name, "description" to description, "type" to type, "groupId" to groupId
    )
}

/**
 * 模板浏览的 sealed 域模型：GROUPS / TEMPLATES 编译期区分。
 * TEMPLATES 的 type/groupId 仍为可空（可选过滤，业务本质）。
 */
private sealed interface TemplateBrowseQuery {
    data object Groups : TemplateBrowseQuery
    data class Templates(val type: String?, val groupId: String?) : TemplateBrowseQuery
}

private data class TemplateBrowseInput(
    @field:JsonPropertyDescription("操作类型：GROUPS 列出所有模板分组，TEMPLATES 按类型/分组查询模板列表。")
    val action: String,
    @field:JsonPropertyDescription("模板类型（CONDITION 或 RULE），仅 action=TEMPLATES 时有效，不指定返回全部。")
    val type: String? = null,
    @field:JsonPropertyDescription("模板分组 ID，仅 action=TEMPLATES 时有效。")
    val groupId: String? = null
) {
    fun toQuery(): TemplateBrowseQuery = when (action.uppercase()) {
        "GROUPS" -> TemplateBrowseQuery.Groups
        "TEMPLATES" -> TemplateBrowseQuery.Templates(type, groupId)
        else -> throw McpBadInput("未知 action: $action，支持 GROUPS / TEMPLATES")
    }
}

private data class SaveTemplateInput(
    @field:JsonPropertyDescription("模板类型：CONDITION 或 RULE。")
    val type: String,
    @field:JsonPropertyDescription("模板名称")
    val name: String,
    @field:JsonPropertyDescription("模板结构 JSON（字符串类型）。只存组件引用（数据源ID、算子ID），不存具体参数值。")
    val contentJson: String,
    @field:JsonPropertyDescription("模板描述")
    val description: String? = null,
    @field:JsonPropertyDescription("模板分组 ID，可选")
    val groupId: String? = null
)
