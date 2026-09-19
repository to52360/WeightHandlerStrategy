package lin.mcp.combo_plan

import com.fasterxml.jackson.annotation.JsonPropertyDescription
import lin.bean.usePlan.ComboRelation
import lin.mcp.*
import lin.mcp.action.*
import lin.repository.HsCardRepository
import lin.repository.card_group.CardGroupService
import lin.repository.combo_plan.ComboPlanDefinitionEntity
import lin.repository.combo_plan.ComboPlanDefinitionRepository
import lin.repository.combo_plan.ComboPlanService
import lin.rule.tree.CardGroupBinding
import lin.rule.tree.CardGroupManagerConfig
import lin.ui.service.TreeConfigService
import lin.utils.nextShortId

/**
 * Combo 域 MCP 工具提供者（写工具 + 动作同文件，一个资源域一个包）：
 * - resource=combo_plan 的 get/list/delete（原 combo_plan 查询 + delete_combo_plan 工具）。
 * - provide()：save_combo_plan 写工具。
 * - DTO 定义见 ComboPlanDtos.kt（供 save / delete 快照 / 动作共用）。
 */
class ComboPlanToolProvider(
    private val repository: ComboPlanDefinitionRepository,
    private val cardGroupService: CardGroupService,
    private val hsCardRepository: HsCardRepository,
    private val treeConfigService: TreeConfigService,
    private val comboPlanService: ComboPlanService
) : McpToolProvider {

    override val actions: List<ResourceActions> = listOf(
        ResourceActions(
            resource = ActionResources.COMBO_PLAN,
            capabilities = listOf(
                GetCapability(
                    fieldHint = "Combo 方案 id（由 list(resource=combo_plan) 返回）"
                ) { id -> comboPlanDetail(id) },
                ListCapability(supportsManagerIdFilter = true) { managerId -> comboPlanSummaries(managerId) },
                DeleteCapability(
                    fieldHint = "Combo 方案 id（由 list(resource=combo_plan) 返回）",
                    semantics = "删除前自动把完整方案落快照（delete_snapshot）并回 snapshotId，可经 restore_snapshot 一键恢复（原 id 保留）",
                    ops = comboPlanService.deleteOps()
                ),
                RestoreCapability { entityId, payload ->
                    comboPlanService.restoreFromSnapshot(entityId, payload)
                }
            )
        )
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
                        ComboRelation.entries.map { it.name }
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
                changeScore = input.changeScore,
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

    // ── 能力实现（combo_plan 的 get / list）：具名私有函数，行为可点名 ──

    /** list：Combo 方案摘要（可按 managerId 过滤，组名解析自卡组绑定）。 */
    private fun comboPlanSummaries(managerId: String?): McpToolResult {
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
                changeScore = entity.changeScore,
                relation = entity.relation,
                coreMutex = entity.coreMutex,
                mustAdjacent = entity.mustAdjacent
            )
        }
        return mcpSuccess(summaries)
    }

    /** get：Combo 方案详情（含组明细、时序步骤、同卡组评估树）。 */
    private fun comboPlanDetail(id: String): McpToolResult {
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
            changeScore = entity.changeScore,
            relation = entity.relation,
            coreMutex = entity.coreMutex,
            mustAdjacent = entity.mustAdjacent,
            sequence = sequence,
            coManagerTrees = coManagerTrees
        )

        return mcpSuccess(detail)
    }

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

private data class SaveComboPlanInput(
    @field:JsonPropertyDescription("卡组/管理器 ID（来自 list(resource=card_group) 返回的 id，或 save_card_group 响应的 managerId），必填")
    val managerId: String,

    @field:JsonPropertyDescription("Combo 方案 ID。若是新建，留空时系统将自动分配短 ID；若是编辑现有方案，传入对应 id")
    val id: String? = null,

    @field:JsonPropertyDescription("核心卡牌分组 ID 列表（绑定条目 id，来自 get(resource=card_group) 的 bindings.id），至少包含一个")
    val coreGroupIds: List<String>,

    @field:JsonPropertyDescription("依赖卡牌分组 ID 列表（绑定条目 id），不能为空。Combo 方案必须包含核心组与依赖组才能构成协同")
    val depGroupIds: List<String> = emptyList(),

    @field:JsonPropertyDescription("Combo 加分（**费值**：1 分 = 0.4 费，如 4.0 ≈ 原 10 分；负值如 -2.0 表达软惩罚）。【纯排序配方：固定 0】仅表达先后顺序不加分时必填 0，此时配合 relation=CORE_BEFORE_DEP/DEP_BEFORE_CORE 即为纯排序（如「先铺场再清场」的出牌次序），不会扭曲权重竞争")
    val score: Double = 0.0,

    @field:JsonPropertyDescription("【起手换牌专用】组合协同加分（费值）：本 Combo 的核心组与依赖组的牌在起手**同时保留**时，给该保留子集额外加此费值——表达「A、B 单留都一般，一起留才值钱」（单卡 changeWeight 只能表达单卡价值）。默认 0 = 不加成。⚠️只影响起手换牌，**不影响出牌评分**（出牌协同加分是 score）；同一个 Combo 在一次起手评分中只计一次")
    val changeScore: Double = 0.0,

    @field:JsonPropertyDescription("核心组内互斥：同 Combo 下多个核心组候选不能同时打出时置为 true。⚠️陷阱一：默认 true——纯排序配方必须显式置 false，否则多核心组会被互斥剪枝、只剩一组参与排序。⚠️陷阱二：该值同时被起手换牌消费（多个核心组不能同时保留），置 false 会连带取消起手侧的保留互斥——若该 Combo 的核心牌在起手阶段也不希望同时留手，需另行确认")
    val coreMutex: Boolean = true,

    @field:JsonPropertyDescription("顺序关系：SCORE_ONLY（纯加分无顺序）/ CORE_BEFORE_DEP（核心组先出）/ DEP_BEFORE_CORE（依赖组先出），默认 SCORE_ONLY。【纯排序配方】score=0 + coreMutex=false + relation=二者之一：表达「A 组的牌恒先于 B 组的牌」，与 orderWeight 的区别是可跨阶段拉动（拓扑约束跑在阶段排序之上）")
    val relation: String = "SCORE_ONLY",

    @field:JsonPropertyDescription("【当前版本无效，请勿配置】必须连续/相邻打出。引擎侧 UsePlanOrderer 尚未消费该语义（强相邻需先设计组块/窗口模型），配置后不产生任何效果，默认 false")
    val mustAdjacent: Boolean = false
)
