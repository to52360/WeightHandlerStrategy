package lin.mcp

import lin.ai.config.AiConfigGenerationService
import lin.mcp.action.*
import lin.repository.combo_plan.ComboPlanDefinitionRepository
import lin.ui.service.TreeConfigService

/**
 * AiTreeConfig 域 MCP 工具提供者（纯动作域，无独立写工具）：
 * - [EvaluatorTreeAction]：resource=evaluator_tree 的 get/list/delete（原 evaluator_tree / delete_evaluator_tree 工具）。
 * - [CapabilityBackgroundAction]：resource=capability_background 的 list（原 list_capability_background 工具，并入 list 大类）。
 * 动作由 get/list/delete/tool_capabilities 四大 dispatcher 收集分发；本类无 provide() 工具。
 */
class AiTreeConfigToolProvider(
    private val treeConfigService: TreeConfigService,
    private val comboPlanDefinitionRepository: ComboPlanDefinitionRepository,
    private val aiConfigGenerationService: AiConfigGenerationService
) : McpToolProvider {

    override val actions: List<ResourceAction> = listOf(
        EvaluatorTreeAction(treeConfigService, comboPlanDefinitionRepository),
        CapabilityBackgroundAction(aiConfigGenerationService)
    )

    override fun provide(): List<McpToolHandler> = emptyList()

    /** evaluator_tree 查询动作。 */
    private class EvaluatorTreeAction(
        private val treeConfigService: TreeConfigService,
        private val comboPlanDefinitionRepository: ComboPlanDefinitionRepository
    ) : GetAction, ListAction, DeleteAction {

        override val resource: String = ActionResources.EVALUATOR_TREE

        override val supportsManagerIdFilter: Boolean = true

        override fun handleList(managerId: String?): McpToolResult {
            return mcpSuccess(
                treeConfigService.loadSummaries(
                    managerId = managerId,
                    limit = if (managerId.isNullOrBlank()) DEFAULT_TREE_LIST_LIMIT else null
                )
            )
        }

        override fun handleGet(id: String): McpToolResult {
            val result = treeConfigService.findById(id)
            if (result?.second == null) {
                return mcpError("树配置不存在: $id")
            }
            val entity = result.first!!
            val config = result.second!!
            val managerId = entity.managerId
            val associatedComboPlans = if (!managerId.isNullOrBlank()) {
                comboPlanDefinitionRepository.findByManagerId(managerId).map { plan ->
                    mapOf(
                        "id" to plan.id,
                        "relation" to plan.relation,
                        "score" to plan.score,
                        "coreGroupIds" to plan.coreGroupIdSet().toList(),
                        "depGroupIds" to plan.depGroupIdSet().toList(),
                        "coreMutex" to plan.coreMutex,
                        "mustAdjacent" to plan.mustAdjacent
                    )
                }
            } else emptyList()

            return mcpSuccess(
                mapOf(
                    "managerId" to managerId,
                    "bindingType" to config.bindingType.name,
                    "bindingIds" to config.bindingIds,
                    "tree" to config.root.toNamed(),
                    "leafConfigs" to config.leafConfigs,
                    "associatedComboPlans" to associatedComboPlans
                )
            )
        }

        override val getFieldHint: String = "树 id（由 list 返回，或 save_card_group/草稿提交产生）"

        override fun handleDelete(id: String): McpToolResult {
            val summaries = treeConfigService.loadSummaries()
            val target = summaries.firstOrNull { it["id"] == id }
                ?: return mcpError(
                    "树不存在: $id。当前存在的树列表: ${
                        summaries.map { mapOf("id" to it["id"], "name" to it["name"]) }
                    }"
                )
            treeConfigService.delete(id)
            return mcpSuccess(mapOf("deleted" to true, "treeId" to id, "treeName" to target["name"]))
        }

        override val deleteFieldHint: String = "树 id（由 list(resource=evaluator_tree) 获取）"

        override val deleteSemantics: String = "删除不可恢复（含叶子配置）"

        companion object {
            /** list 无 managerId 过滤时的返回条数上限（原 evaluator_tree 工具的 LIST 语义）。 */
            const val DEFAULT_TREE_LIST_LIMIT = 50
        }
    }

    /** capability_background 列表动作（只读）。 */
    private class CapabilityBackgroundAction(
        private val service: AiConfigGenerationService
    ) : ListAction {

        override val resource: String = ActionResources.CAPABILITY_BACKGROUND

        override val supportsManagerIdFilter: Boolean = true

        override fun handleList(managerId: String?): McpToolResult {
            return mcpSuccess(service.listCapabilityBackground(managerId))
        }
    }
}
