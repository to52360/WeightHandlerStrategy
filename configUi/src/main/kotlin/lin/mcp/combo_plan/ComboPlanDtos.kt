package lin.mcp.combo_plan

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
