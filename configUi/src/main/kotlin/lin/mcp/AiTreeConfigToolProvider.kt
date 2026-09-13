package lin.mcp

import lin.ai.config.AiConfigGenerationService
import lin.mcp.action.*
import lin.repository.card_group.CardGroupService
import lin.repository.combo_plan.ComboPlanDefinitionRepository
import lin.ui.service.TreeConfigService

/**
 * AiTreeConfig 域 MCP 工具提供者（纯动作域，无独立写工具）：
 * - resource=evaluator_tree 的 get/list/delete（原 evaluator_tree / delete_evaluator_tree 工具）。
 * - resource=capability_background 的 list（原 list_capability_background 工具，并入 list 大类）。
 * 动作由 get/list/delete/tool_capabilities 四大 dispatcher 收集分发；本类无 provide() 工具。
 */
class AiTreeConfigToolProvider(
    private val treeConfigService: TreeConfigService,
    private val comboPlanDefinitionRepository: ComboPlanDefinitionRepository,
    private val aiConfigGenerationService: AiConfigGenerationService,
    /** T-TG-023：恢复路由的归属卡组存在性判定（本 Provider 是唯一消费卡组域的恢复入口）。 */
    private val cardGroupService: CardGroupService
) : McpToolProvider {

    override val actions: List<ResourceActions> = listOf(
        ResourceActions(
            resource = ActionResources.EVALUATOR_TREE,
            capabilities = listOf(
                GetCapability(
                    fieldHint = "树 id（由 list 返回，或 save_card_group/草稿提交产生）"
                ) { id -> treeDetail(id) },
                ListCapability(supportsManagerIdFilter = true) { managerId -> treeSummaries(managerId) },
                DeleteCapability(
                    fieldHint = "树 id（由 list(resource=evaluator_tree) 获取）",
                    semantics = "删除前落快照（delete_snapshot，含 root/leafConfigs）并回 snapshotId，可经 restore_snapshot 一键恢复（原 id 保留）",
                    ops = treeConfigService.deleteOps()
                ),
                RestoreCapability { entityId, payload ->
                    // T-TG-023：归属卡组已不存在 ⇒ 恢复被拒（否则造出"悬空归属"，该树对任何卡组都不生效）
                    treeConfigService.restoreFromSnapshot(entityId, payload) { managerId ->
                        cardGroupService.loadAll(onlyEnabled = false).any { it.cardGroupManagerId == managerId }
                    }
                }
            )
        ),
        ResourceActions(
            resource = ActionResources.CAPABILITY_BACKGROUND,
            capabilities = listOf(
                ListCapability(supportsManagerIdFilter = true) { managerId -> capabilityBackground(managerId) }
            )
        )
    )

    override fun provide(): List<McpToolHandler> = emptyList()

    // ── 能力实现（evaluator_tree / capability_background）：具名私有函数，行为可点名 ──

    /** list：评估树摘要（无 managerId 时限制条数）。 */
    private fun treeSummaries(managerId: String?): McpToolResult {
        return mcpSuccess(
            treeConfigService.loadSummaries(
                managerId = managerId,
                limit = if (managerId.isNullOrBlank()) DEFAULT_TREE_LIST_LIMIT else null
            )
        )
    }

    /** get：评估树详情（树拓扑 + 叶子配置 + 关联 Combo 方案）。 */
    private fun treeDetail(id: String): McpToolResult {
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

    /** list：背景能力清单（评估生成的背景信息）。 */
    private fun capabilityBackground(managerId: String?): McpToolResult =
        mcpSuccess(aiConfigGenerationService.listCapabilityBackground(managerId))

    private companion object {
        /** list 无 managerId 过滤时的返回条数上限（原 evaluator_tree 工具的 LIST 语义）。 */
        const val DEFAULT_TREE_LIST_LIMIT = 50
    }
}
