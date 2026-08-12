package lin.mcp

import com.fasterxml.jackson.annotation.JsonPropertyDescription
import lin.bean.usePlan.ComboRelation
import lin.mcp.action.*
import lin.repository.HsCardRepository
import lin.repository.card_group.CardGroupService
import lin.repository.combo_plan.ComboPlanDefinitionEntity
import lin.repository.combo_plan.ComboPlanDefinitionRepository
import lin.rule.tree.CardGroupBinding
import lin.rule.tree.CardGroupManagerConfig
import lin.ui.service.TreeConfigService
import lin.utils.nextShortId

/**
 * Combo 域 MCP 工具提供者（写工具 + 动作同文件，一个资源域一个 Provider）：
 * - [ComboPlanAction]：resource=combo_plan 的 get/list/delete（原 combo_plan 查询 + delete_combo_plan）。
 * - provide()：save_combo_plan 写工具。
 * - DTO 定义（ComboPlanSummaryDto 等）供 save / delete 快照 / action 共用。
 */

// ── DTO ──

/** 分组引用关系（包含组 ID 集合与解析后的组名列表） */
data class ComboPlanGroupRef(
    val ids: Set<String>,
    val names: List<String>
)

/** 分组详细信息（包含组名、卡牌 ID 列表与卡牌中文名） */
data class ComboPlanGroupDto(
    val groupId: String,
    val groupName: String,
    val cardIds: List<String>,
    val cardNames: List<String>
)

/** 出牌步骤序列 */
data class ComboStepDto(
    val stepNumber: Int,
    val phaseName: String,    // e.g. "先手核心组", "后手跟随依赖组"
    val description: String,  // e.g. "先打出核心组 [组名A, 组名B]"
    val groupIds: List<String>,
    val groups: List<ComboPlanGroupDto>
)

/** Summary 摘要响应 DTO（使用 ComboPlanGroupRef 模块化结构） */
data class ComboPlanSummaryDto(
    val id: String,
    val managerId: String,
    val managerName: String?,
    val coreGroup: ComboPlanGroupRef,
    val depGroup: ComboPlanGroupRef,
    val score: Double,
    val relation: String,
    val coreMutex: Boolean,
    val mustAdjacent: Boolean
)

/** 同一卡组 Manager 下的评估树摘要（卡组级关联视图） */
data class CoManagerTreeSummaryDto(
    val id: String,
    val name: String,
    val bindingType: String
)

/** Detail 详细响应 DTO */
data class ComboPlanDetailDto(
    val id: String,
    val managerId: String,
    val managerName: String?,
    val coreGroups: List<ComboPlanGroupDto>,
    val depGroups: List<ComboPlanGroupDto>,
    val score: Double,
    val relation: String,        // SCORE_ONLY, CORE_BEFORE_DEP, DEP_BEFORE_CORE
    val coreMutex: Boolean,
    val mustAdjacent: Boolean,
    val sequence: List<ComboStepDto>,
    val coManagerTrees: List<CoManagerTreeSummaryDto> = emptyList()
)

// ── Provider ──

class ComboPlanToolProvider(
    private val repository: ComboPlanDefinitionRepository,
    private val cardGroupService: CardGroupService,
    private val hsCardRepository: HsCardRepository,
    private val treeConfigService: TreeConfigService
) : McpToolProvider {

    override val actions: List<ResourceAction> = listOf(
        ComboPlanAction(repository, cardGroupService, hsCardRepository, treeConfigService)
    )

    override fun provide(): List<McpToolHandler> = listOf(
        typedTool<SaveComboPlanInput>(
            name = "save_combo_plan",
            description = """
                创建或修改 Combo 战术编排方案。
                会校验 managerId 以及 coreGroupIds/depGroupIds 是否属于该卡组的有效绑定条目；
                id 为空时会自动分配 short ID；如果包含已有 id，则会覆盖保存该 Combo 方案。
            """.trimIndent()
        ) { input ->
            val allManagers = cardGroupService.loadAll(onlyEnabled = false)
            val manager = allManagers.find { m -> m.cardGroupManagerId == input.managerId }
                ?: return@typedTool mcpError(
                    "卡组/管理器不存在: ${input.managerId}。当前存在的卡组列表: ${
                        allManagers.map {
                            mapOf(
                                "id" to it.cardGroupManagerId,
                                "name" to it.name
                            )
                        }
                    }"
                )

            if (input.coreGroupIds.isEmpty()) {
                return@typedTool mcpError("coreGroupIds 核心分组 ID 列表不能为空，必须包含至少一个有效分组 ID")
            }

            if (input.depGroupIds.isEmpty()) {
                return@typedTool mcpError("depGroupIds 依赖分组 ID 列表不能为空！Combo 方案必须包含核心组与依赖组才能构成协同加分或时序关联。")
            }

            val validBindingIds = manager.bindings.map { b -> b.id }.toSet()
            val invalidCoreIds = input.coreGroupIds.filterNot { it in validBindingIds }
            val invalidDepIds = input.depGroupIds.filterNot { it in validBindingIds }

            if (invalidCoreIds.isNotEmpty() || invalidDepIds.isNotEmpty()) {
                return@typedTool mcpError(
                    "提供的分组 ID 不属于卡组 [${manager.name}] (${input.managerId})。" +
                            "无效核心组: $invalidCoreIds, 无效依赖组: $invalidDepIds。" +
                            "该卡组下的有效分组绑定列表: ${
                                manager.bindings.map {
                                    mapOf(
                                        "id" to it.id,
                                        "name" to it.name
                                    )
                                }
                            }"
                )
            }

            val relationEnum = try {
                ComboRelation.valueOf(input.relation.uppercase())
            } catch (_: Exception) {
                return@typedTool mcpError(
                    "未知 relation: ${input.relation}。合法选项: ${
                        ComboRelation.values().map { it.name }
                    }"
                )
            }

            val finalId = input.id?.trim()?.takeIf { it.isNotEmpty() } ?: nextShortId()

            val entity = ComboPlanDefinitionEntity(
                managerId = input.managerId,
                id = finalId,
                coreGroupIds = input.coreGroupIds.joinToString(","),
                depGroupIds = input.depGroupIds.joinToString(","),
                score = input.score,
                coreMutex = input.coreMutex,
                relation = relationEnum.name,
                mustAdjacent = input.mustAdjacent
            )

            repository.save(entity)

            mcpSuccess(
                mapOf(
                    "saved" to true,
                    "id" to finalId,
                    "managerId" to input.managerId,
                    "managerName" to manager.name
                )
            )
        }
    )

    // ── 动作：combo_plan get/list/delete ──

    private class ComboPlanAction(
        private val repository: ComboPlanDefinitionRepository,
        private val cardGroupService: CardGroupService,
        private val hsCardRepository: HsCardRepository,
        private val treeConfigService: TreeConfigService
    ) : GetAction, ListAction, DeleteAction {

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
}

private data class SaveComboPlanInput(
    @field:JsonPropertyDescription("卡组/管理器 ID（来自 list(resource=card_group) 返回的 id，或 save_card_group 响应的 managerId），必填")
    val managerId: String,

    @field:JsonPropertyDescription("Combo 方案 ID。若是新建，留空时系统将自动分配短 ID；若是编辑现有方案，传入对应 id")
    val id: String? = null,

    @field:JsonPropertyDescription("核心卡牌分组 ID 列表（绑定条目 id，来自 get(resource=card_group) 的 bindings.id），至少包含一个")
    val coreGroupIds: List<String>,

    @field:JsonPropertyDescription("依赖卡牌分组 ID 列表（绑定条目 id），不能为空。Combo 方案必须包含核心组与依赖组才能构成协同")
    val depGroupIds: List<String> = emptyList(),

    @field:JsonPropertyDescription("Combo 额外权重/加分（如 3.0，负值如 -2.0 可表达软惩罚）")
    val score: Double = 0.0,

    @field:JsonPropertyDescription("核心组内互斥：同 Combo 下多个核心组候选不能同时打出时置为 true，默认 true")
    val coreMutex: Boolean = true,

    @field:JsonPropertyDescription("顺序关系：SCORE_ONLY（纯加分无顺序）, CORE_BEFORE_DEP（核心组先手）, DEP_BEFORE_CORE（依赖组先手），默认 SCORE_ONLY")
    val relation: String = "SCORE_ONLY",

    @field:JsonPropertyDescription("必须连续/相邻打出，默认 false")
    val mustAdjacent: Boolean = false
)
