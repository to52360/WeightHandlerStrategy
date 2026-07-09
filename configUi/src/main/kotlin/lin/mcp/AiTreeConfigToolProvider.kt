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
            name = "list_capability_background",
            description = """
                【能力背景 / 规划前置】列出系统当前真实存在的全部可编排能力，按领域分组：
                codedRules（预编码规则）、plainConditions（预编码条件）、conditionTrees（条件树），
                每项含 sourceId、name、desc 以及该能力需要的属性 requiredProperties。
                AI 必须在编排卡牌分组、构建评估树之前先调用本工具，依据真实存在的 sourceId 与属性来规划，
                严禁凭空捏造规则/条件 ID 或属性字段，否则会在提交时被校验拒绝（幻觉）。
                正交能力（orthogonal_condition / orthogonal_rule）的底层积木与类型链路不在此展开，
                构造正交叶子时再调用 list_orthogonal_components 获取精细细节。
            """.trimIndent(),
            inputSchemaJson = """{"type":"object","properties":{}}""",
            call = {
                McpToolResult(mapper.writeValueAsString(service.listCapabilityBackground()))
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
