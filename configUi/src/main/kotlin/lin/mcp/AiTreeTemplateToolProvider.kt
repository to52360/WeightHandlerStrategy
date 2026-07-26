package lin.mcp

import com.fasterxml.jackson.annotation.JsonPropertyDescription
import lin.repository.tree_config.EvaluatorTreeTemplateEntity
import lin.repository.tree_config.EvaluatorTreeTemplateRepository
import lin.ui.service.EvaluatorTreeTemplateService
import lin.utils.nextShortId

/**
 * 评估树模板 MCP 工具提供者。
 * 专管评估树模板（树级）查询/沉淀。
 */
class AiTreeTemplateToolProvider(
    private val treeTemplateRepo: EvaluatorTreeTemplateRepository,
    private val treeTemplateService: EvaluatorTreeTemplateService
) : McpToolProvider {
    override fun provide(): List<McpToolHandler> = listOf(
        // ── tree_template: 模板列表 + 详情 (合并) ──
        typedTool<TreeTemplateInput>(
            name = "tree_template",
            description = "查询评估树模板。支持 action=LIST（列出所有模板 id/name/description 摘要）和 action=GET（读取模板完整骨架：树拓扑+叶子配置字典，可作为 create_draft_tree 的起点）。"
        ) { input ->
            when (val query = input.toQuery()) {
                is TreeTemplateQuery.ListAction -> {
                    mcpSuccess(treeTemplateRepo.findAll().map { it.toSummary() })
                }

                is TreeTemplateQuery.GetAction -> {
                    val result = treeTemplateService.findById(query.id)
                    if (result?.second == null) {
                        mcpError("模板不存在")
                    } else {
                        val config = result.second!!
                        mcpSuccess(
                            mapOf(
                                "bindingType" to config.bindingType.name,
                                "bindingIds" to config.bindingIds,
                                "tree" to config.root.toNamed(),
                                "leafConfigs" to config.leafConfigs
                            )
                        )
                    }
                }
            }
        },

        // ── save_evaluator_tree_template (保留) ──
        typedTool<SaveTreeTemplateInput>(
            name = "save_evaluator_tree_template",
            description = "将当前生成的评估树沉淀为可复用模板。只需提供树骨架结构（叶子节点类型和引用关系），具体参数值不需要保存。"
        ) { input ->
            val entity = EvaluatorTreeTemplateEntity(
                id = nextShortId(), name = input.name, description = input.description,
                groupId = input.groupId, configData = input.contentJson
            )
            treeTemplateRepo.save(entity)
            mcpSuccess(mapOf("id" to entity.id, "name" to entity.name))
        }
    )

    private fun EvaluatorTreeTemplateEntity.toSummary(): Map<String, Any?> = mapOf(
        "id" to id, "name" to name, "description" to description, "groupId" to groupId
    )
}

private sealed interface TreeTemplateQuery {
    data object ListAction : TreeTemplateQuery
    data class GetAction(val id: String) : TreeTemplateQuery
}

private data class TreeTemplateInput(
    @field:JsonPropertyDescription("操作类型：LIST 列出所有模板摘要，GET 读取模板详情（需传 id）")
    val action: String,
    @field:JsonPropertyDescription("模板 id，仅 action=GET 时需要，由 tree_template(action=LIST) 返回。")
    val id: String? = null
) {
    fun toQuery(): TreeTemplateQuery = when (action.uppercase()) {
        "GET" -> {
            val id = id
            if (id.isNullOrBlank()) throw McpBadInput("action=GET 需要 id 参数") else TreeTemplateQuery.GetAction(id)
        }

        "LIST" -> TreeTemplateQuery.ListAction
        else -> throw McpBadInput("未知 action: $action，支持 LIST / GET")
    }
}
