package lin.mcp

import com.fasterxml.jackson.annotation.JsonPropertyDescription
import lin.bean.usePlan.ComboRelation
import lin.db.HsCardRepository
import lin.rule.tree.CardGroupBinding
import lin.rule.tree.CardGroupManagerConfig
import lin.ui.card_group.db.CardGroupService
import lin.ui.combo_plan.db.ComboPlanDefinitionRepository

private data class ComboPlanInput(
    @field:JsonPropertyDescription("操作类型：LIST（列出所有 Combo 方案摘要），GET（读取指定 Combo 的详细依赖与步骤）。有效值仅限：LIST, GET")
    val action: String,

    @field:JsonPropertyDescription("Combo 方案 ID，仅 action=GET 时必填")
    val id: String? = null,

    @field:JsonPropertyDescription("卡组/管理器 ID，仅 action=LIST 时可选，用于按卡组过滤")
    val managerId: String? = null
)

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

private data class GroupContext(
    val managerMap: Map<String, CardGroupManagerConfig>,
    val bindingMap: Map<String, CardGroupBinding>
)

class ComboPlanToolProvider(
    private val repository: ComboPlanDefinitionRepository,
    private val cardGroupService: CardGroupService,
    private val hsCardRepository: HsCardRepository,
    private val treeConfigService: lin.ui.service.TreeConfigService
) : McpToolProvider {

    override fun provide(): List<McpToolHandler> = listOf(
        typedTool<ComboPlanInput>(
            name = "combo_plan",
            description = """
                查询 Combo 战术编排。
                支持 action=LIST（列出已保存 Combo 方案摘要，可选按 managerId 过滤卡组），
                以及 action=GET（读取指定 Combo 的完整依赖卡牌分组信息与时序出牌步骤 sequence）。
            """.trimIndent()
        ) { input ->
            when (input.action.uppercase()) {
                "LIST" -> handleList(input.managerId)
                "GET" -> handleGet(input.id)
                else -> mcpError("未知 action: ${input.action}，支持 LIST / GET")
            }
        }
    )

    private fun loadGroupContext(): GroupContext {
        val allManagers = cardGroupService.loadAll(onlyEnabled = false)
        return GroupContext(
            managerMap = allManagers.associateBy { m -> m.cardGroupManagerId },
            bindingMap = allManagers.flatMap { m -> m.bindings }.associateBy { b -> b.id }
        )
    }

    private fun handleList(filterManagerId: String?): McpToolResult {
        val entities = if (!filterManagerId.isNullOrBlank()) {
            repository.findByManagerId(filterManagerId)
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

    private fun handleGet(id: String?): McpToolResult {
        if (id.isNullOrBlank()) {
            return mcpError("action=GET 需要 id 参数")
        }

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
