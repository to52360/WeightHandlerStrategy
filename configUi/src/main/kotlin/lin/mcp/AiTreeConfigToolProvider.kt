package lin.mcp

import com.fasterxml.jackson.databind.ObjectMapper
import lin.ai.config.AiConfigGenerationService
import lin.ui.service.TreeConfigService

/**
 * 评估树配置 MCP 工具提供者。
 * 专管评估树叶子元数据、正式树查询。
 */
class AiTreeConfigToolProvider(
    private val service: AiConfigGenerationService,
    private val treeConfigService: TreeConfigService,
    private val mapper: ObjectMapper
) : McpToolProvider {
    override fun provide(): List<McpToolHandler> = listOf(
        McpToolHandler(
            name = "list_evaluator_leaf_kinds",
            description = "列出 AI 生成评估树可使用的叶子种类（规则、条件、条件树）。对于正交类型，具体组合算子请看 list_orthogonal_components。",
            inputSchemaJson = """{"type":"object","properties":{}}""",
            call = {
                McpToolResult(mapper.writeValueAsString(service.listEvaluatorLeafKinds()))
            }
        ),
        McpToolHandler(
            name = "list_evaluator_trees",
            description = "列出所有已保存的正式评估树（返回 id/name/bindingType 等摘要），用于检索你想要编辑的目标树。",
            inputSchemaJson = """{"type":"object","properties":{}}""",
            call = {
                val summaries = treeConfigService.loadSummaries()
                McpToolResult(mapper.writeValueAsString(summaries))
            }
        ),
        typedTool<GetTreeRequest>(
            name = "get_evaluator_tree",
            description = "读取某个现有的评估树正式配置。当你想要修改现有树时，可以先调用此工具获取骨架，然后用 create_draft_tree 开启修改流程。",
            mapper = mapper
        ) { input ->
            val result = treeConfigService.findById(input.id)
            if (result?.second == null) {
                McpToolResult("Tree config not found", isError = true)
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
        }
    )
}
