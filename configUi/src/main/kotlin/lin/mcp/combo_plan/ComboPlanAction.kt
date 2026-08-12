package lin.mcp.combo_plan

import lin.bean.usePlan.ComboRelation
import lin.mcp.McpToolResult
import lin.mcp.action.ActionResources
import lin.mcp.action.DeleteAction
import lin.mcp.action.GetAction
import lin.mcp.action.ListAction
import lin.mcp.mcpError
import lin.mcp.mcpSuccess
import lin.repository.HsCardRepository
import lin.repository.card_group.CardGroupService
import lin.repository.combo_plan.ComboPlanDefinitionRepository
import lin.rule.tree.CardGroupBinding
import lin.rule.tree.CardGroupManagerConfig
import lin.ui.service.TreeConfigService
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * combo_plan 资源动作：get/list/delete（原 combo_plan 查询 + delete_combo_plan 工具）。
 * 由 get/list/delete/tool_capabilities 四大 dispatcher 收集分发；写工具在 [ComboPlanToolProvider]。
 * 依赖分工：Provider 主类已有的依赖（repository/cardGroupService）构造传入；
 * Provider 没有的（hsCardRepository/treeConfigService）内部 inject 自取。
 */
class ComboPlanAction(
    private val repository: ComboPlanDefinitionRepository,
    private val cardGroupService: CardGroupService
) : GetAction, ListAction, DeleteAction, KoinComponent {

    private val hsCardRepository: HsCardRepository by inject()
    private val treeConfigService: TreeConfigService by inject()

    override val resource: String = ActionResources.COMBO_PLAN

    override fun handleList(managerId: String?): McpToolResult {
        val entities = if (!managerId.isNullOrBlank()) {
            repository.findByManagerId(managerId)
        } else {
            repository.findAll()
        }

        val ctx = loadGroupContext()
        val summaries = entities.map { entity ->
            val manager = ctx.managerMap[entity.managerId]
            val coreGroupNames = entity.coreGroupIdSet().map { ctx.bindingMap[it]?.name ?: it }
            val depGroupNames = entity.depGroupIdSet().map { ctx.bindingMap[it]?.name ?: it }

            ComboPlanSummaryDto(
                id = entity.id,
                managerId = entity.managerId,
                managerName = manager?.name,
                coreGroup = ComboPlanGroupRef(ids = entity.coreGroupIdSet(), names = coreGroupNames),
                depGroup = ComboPlanGroupRef(ids = entity.depGroupIdSet(), names = depGroupNames),
                score = entity.score,
                relation = entity.relation,
                coreMutex = entity.coreMutex,
                mustAdjacent = entity.mustAdjacent
            )
        }
        return mcpSuccess(summaries)
    }

    override fun handleGet(id: String): McpToolResult {
        val entity = repository.findById(id)
            ?: run {
                val availableIds = repository.findAll().map { e -> e.id }
                return mcpError("Combo 方案不存在: $id。现有 Combo 方案 ID 列表: $availableIds")
            }

        val ctx = loadGroupContext()
        val manager = ctx.managerMap[entity.managerId]

        val coreGroups = resolveGroupDetails(ctx, entity.coreGroupIdSet())
        val depGroups = resolveGroupDetails(ctx, entity.depGroupIdSet())

        val relationEnum = try {
            ComboRelation.valueOf(entity.relation)
        } catch (_: Exception) {
            ComboRelation.SCORE_ONLY
        }

        val sequence = buildSequence(relationEnum, coreGroups, depGroups)
        val coManagerTrees = findCoManagerTrees(entity.managerId)

        val detail = ComboPlanDetailDto(
            id = entity.id,
            managerId = entity.managerId,
            managerName = manager?.name,
            coreGroups = coreGroups,
            depGroups = depGroups,
            score = entity.score,
            relation = entity.relation,
            coreMutex = entity.coreMutex,
            mustAdjacent = entity.mustAdjacent,
            sequence = sequence,
            coManagerTrees = coManagerTrees
        )

        return mcpSuccess(detail)
    }

    override val getFieldHint: String = "Combo 方案 id（由 list(resource=combo_plan) 返回）"

    override fun handleDelete(id: String): McpToolResult {
        val entity = repository.findById(id)
            ?: run {
                val available = repository.findAll().map { mapOf("id" to it.id, "managerId" to it.managerId) }
                return mcpError("Combo 方案不存在: $id。当前存在的 Combo 列表: $available")
            }

        val allManagers = cardGroupService.loadAll(onlyEnabled = false)
        val manager = allManagers.find { m -> m.cardGroupManagerId == entity.managerId }
        val bindingMap = allManagers.flatMap { m -> m.bindings }.associateBy { b -> b.id }

        val coreNames = entity.coreGroupIdSet().map { bindingMap[it]?.name ?: it }
        val depNames = entity.depGroupIdSet().map { bindingMap[it]?.name ?: it }

        val deletedSnapshot = ComboPlanSummaryDto(
            id = entity.id,
            managerId = entity.managerId,
            managerName = manager?.name,
            coreGroup = ComboPlanGroupRef(ids = entity.coreGroupIdSet(), names = coreNames),
            depGroup = ComboPlanGroupRef(ids = entity.depGroupIdSet(), names = depNames),
            score = entity.score,
            relation = entity.relation,
            coreMutex = entity.coreMutex,
            mustAdjacent = entity.mustAdjacent
        )

        repository.deleteById(id)

        return mcpSuccess(
            mapOf(
                "deleted" to true,
                "id" to id,
                "deletedPlan" to deletedSnapshot,
                "recoveryHint" to "若需撤销删除，请将 deletedPlan 中核心与依赖组 ids 提取为 coreGroupIds/depGroupIds，直接调用 save_combo_plan 重新保存"
            )
        )
    }

    override val deleteFieldHint: String = "Combo 方案 id（由 list(resource=combo_plan) 返回）"

    override val deleteSemantics: String = "删除前自动返回 deletedPlan 完整快照，可经 save_combo_plan 无损恢复"

    private data class GroupContext(
        val managerMap: Map<String, CardGroupManagerConfig>,
        val bindingMap: Map<String, CardGroupBinding>
    )

    private fun loadGroupContext(): GroupContext {
        val allManagers = cardGroupService.loadAll(onlyEnabled = false)
        return GroupContext(
            managerMap = allManagers.associateBy { m -> m.cardGroupManagerId },
            bindingMap = allManagers.flatMap { m -> m.bindings }.associateBy { b -> b.id }
        )
    }

    private fun resolveGroupDetails(ctx: GroupContext, groupIds: Set<String>): List<ComboPlanGroupDto> {
        return groupIds.map { groupId ->
            val binding = ctx.bindingMap[groupId]
            val cardIds = binding?.cardIds ?: emptyList()
            val cardNames = if (cardIds.isNotEmpty()) {
                val cardMap = hsCardRepository.findCardByIds(cardIds).associateBy { c -> c.cardId }
                cardIds.map { cardId -> cardMap[cardId]?.name ?: cardId }
            } else emptyList()

            ComboPlanGroupDto(
                groupId = groupId,
                groupName = binding?.name ?: groupId,
                cardIds = cardIds,
                cardNames = cardNames
            )
        }
    }

    private fun findCoManagerTrees(managerId: String): List<CoManagerTreeSummaryDto> {
        return treeConfigService.loadSummaries()
            .filter { t -> t["managerId"] == managerId }
            .map { t ->
                CoManagerTreeSummaryDto(
                    id = t["id"] as String,
                    name = t["name"] as String,
                    bindingType = t["bindingType"] as String
                )
            }
    }

    private fun buildSequence(
        relation: ComboRelation,
        coreGroups: List<ComboPlanGroupDto>,
        depGroups: List<ComboPlanGroupDto>
    ): List<ComboStepDto> {
        val coreNames = coreGroups.joinToString("、") { g -> g.groupName }
        val depNames = depGroups.joinToString("、") { g -> g.groupName }

        return when (relation) {
            ComboRelation.SCORE_ONLY -> listOf(
                ComboStepDto(
                    stepNumber = 1,
                    phaseName = "纯加分组合（无强制时序,还兼顾起手手牌组合加权）",
                    description = "核心组 [$coreNames] 与依赖组 [$depNames] 可按任意顺序打出，组合成立时附带额外加分",
                    groupIds = (coreGroups + depGroups).map { g -> g.groupId },
                    groups = coreGroups + depGroups
                )
            )

            ComboRelation.CORE_BEFORE_DEP -> listOf(
                ComboStepDto(
                    stepNumber = 1,
                    phaseName = "先手核心组",
                    description = "优先打出核心组 [$coreNames]",
                    groupIds = coreGroups.map { g -> g.groupId },
                    groups = coreGroups
                ),
                ComboStepDto(
                    stepNumber = 2,
                    phaseName = "后手跟随依赖组",
                    description = "跟随打出依赖组 [$depNames]",
                    groupIds = depGroups.map { g -> g.groupId },
                    groups = depGroups
                )
            )

            ComboRelation.DEP_BEFORE_CORE -> listOf(
                ComboStepDto(
                    stepNumber = 1,
                    phaseName = "先手铺垫依赖组",
                    description = "优先打出依赖组 [$depNames] 进行铺垫/前置准备",
                    groupIds = depGroups.map { g -> g.groupId },
                    groups = depGroups
                ),
                ComboStepDto(
                    stepNumber = 2,
                    phaseName = "后手跟进核心组",
                    description = "随后打出核心组 [$coreNames]",
                    groupIds = coreGroups.map { g -> g.groupId },
                    groups = coreGroups
                )
            )
        }
    }
}
