package lin.mcp

import com.fasterxml.jackson.databind.ObjectMapper
import lin.ui.service.EvaluatorTreeTemplateService
import lin.ui.tree_config.db.EvaluatorTreeTemplateEntity
import lin.ui.tree_config.db.EvaluatorTreeTemplateRepository
import lin.utils.nextShortId

/**
 * 评估树模板 MCP 工具提供者。
 * 专管评估树模板（树级）查询/沉淀。
 */
class AiTreeTemplateToolProvider(
    private val treeTemplateRepo: EvaluatorTreeTemplateRepository,
    private val treeTemplateService: EvaluatorTreeTemplateService,
    private val mapper: ObjectMapper
) : McpToolProvider {
    override fun provide(): List<McpToolHandler> = listOf(
        McpToolHandler(
            name = "list_evaluator_tree_templates",
            description = "列出所有评估树模板（树级结构快照），返回摘要（id/name/description/groupId）。",
            inputSchemaJson = """{"type":"object","properties":{}}""",
            call = {
                val templates = treeTemplateRepo.findAll().map { it.toSummary() }
                McpToolResult(mapper.writeValueAsString(templates))
            }
        ),
        typedTool<GetTreeRequest>(
            name = "get_evaluator_tree_template",
            description = "读取某个评估树模板的详情。返回包含树的拓扑逻辑骨架（带有节点自描述名称）和剥离了参数的叶子配置字典。大模型应在 create_draft_tree 时利用此骨架。",
            mapper = mapper
        ) { input ->
            val result = treeTemplateService.findById(input.id)
            if (result?.second == null) {
                McpToolResult("Template not found", isError = true)
            } else {
                val config = result.second!!
                val response = mapOf(
                    "bindingType" to config.bindingType.name,
                    "bindingIds" to config.bindingIds,
                    "tree" to config.root.toNamed(),
                    "leafConfigs" to config.leafConfigs
                )
                McpToolResult(mapper.writeValueAsString(response))
            }
        },
        typedTool<SaveTreeTemplateInput>(
            name = "save_evaluator_tree_template",
            description = "将当前生成的评估树沉淀为可复用模板。当你判断某棵树的逻辑结构有复用价值时调用。只需提供树骨架结构（叶子节点类型和引用关系），具体参数值不需要保存。",
            mapper = mapper
        ) { input ->
            val entity = EvaluatorTreeTemplateEntity(
                id = nextShortId(),
                name = input.name,
                description = input.description,
                groupId = input.groupId,
                configData = input.contentJson
            )
            treeTemplateRepo.save(entity)
            McpToolResult(mapper.writeValueAsString(mapOf("id" to entity.id, "name" to entity.name)))
        }
    )

    private fun EvaluatorTreeTemplateEntity.toSummary(): Map<String, Any?> = mapOf(
        "id" to id,
        "name" to name,
        "description" to description,
        "groupId" to groupId
    )
}
