package lin.mcp

import lin.mcp.action.ActionResources
import lin.mcp.action.GetAction
import lin.mcp.action.ListAction
import lin.mcp.action.ResourceAction
import lin.repository.tree_config.EvaluatorTreeTemplateEntity
import lin.repository.tree_config.EvaluatorTreeTemplateRepository
import lin.ui.service.EvaluatorTreeTemplateService
import lin.utils.nextShortId

/**
 * 评估树模板域 MCP 工具提供者（写工具 + 动作同文件）：
 * - [TreeTemplateAction]：resource=tree_template 的 get/list（原 tree_template 工具）。
 * - provide()：save_evaluator_tree_template 沉淀工具。
 */
class AiTreeTemplateToolProvider(
    private val treeTemplateRepo: EvaluatorTreeTemplateRepository,
    private val treeTemplateService: EvaluatorTreeTemplateService
) : McpToolProvider {

    override val actions: List<ResourceAction> = listOf(
        TreeTemplateAction(treeTemplateRepo, treeTemplateService)
    )

    override fun provide(): List<McpToolHandler> = listOf(
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

    // ── 动作：tree_template get/list ──

    private class TreeTemplateAction(
        private val treeTemplateRepo: EvaluatorTreeTemplateRepository,
        private val treeTemplateService: EvaluatorTreeTemplateService
    ) : GetAction, ListAction {

        override val resource: String = ActionResources.TREE_TEMPLATE

        override fun handleList(managerId: String?): McpToolResult {
            return mcpSuccess(treeTemplateRepo.findAll().map { it.toSummary() })
        }

        override fun handleGet(id: String): McpToolResult {
            val result = treeTemplateService.findById(id)
            if (result?.second == null) {
                return mcpError("模板不存在: $id")
            }
            val config = result.second!!
            return mcpSuccess(
                mapOf(
                    "bindingType" to config.bindingType.name,
                    "bindingIds" to config.bindingIds,
                    "tree" to config.root.toNamed(),
                    "leafConfigs" to config.leafConfigs
                )
            )
        }

        override val getFieldHint: String = "模板 id（由 list(resource=tree_template) 返回）"

        private fun EvaluatorTreeTemplateEntity.toSummary(): Map<String, Any?> = mapOf(
            "id" to id, "name" to name, "description" to description, "groupId" to groupId
        )
    }
}
