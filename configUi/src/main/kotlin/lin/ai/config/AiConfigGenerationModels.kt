package lin.ai.config

import com.fasterxml.jackson.annotation.JsonPropertyDescription
import lin.rule.parse.FieldConstraint
import lin.rule.parse.FieldSpec
import lin.rule.parse.FieldType
import lin.rule.tree.EvaluatorTreeConfig

/**
 * AI 配置生成的最小业务入口。
 * MCP 只负责协议适配，本接口负责暴露"AI 需要知道什么、提交什么、得到什么反馈"。
 * 仅聚焦评估树领域（叶子查询 / 校验 / 保存），卡池查询已拆到 CardGroupQueryService。
 */
interface AiConfigGenerationService {
    /**
     * 能力背景（规划前置）。
     * 返回系统当前真实存在的全部可编排能力，按领域分组，并标注每种能力所需的属性。
     * AI 必须在编排卡牌分组、构建评估树之前先调用本方法，依据真实 sourceId 与属性规划，
     * 严禁凭空捏造规则/条件 ID 或属性。正交能力的底层积木细节不在此暴露，见 [list_orthogonal_components]（构造阶段再查）。
     * @param managerId 非空时条件树按"当前卡组私有 + 全局共享"过滤（且排除一次性树）；为空则全量返回。
     */
    fun listCapabilityBackground(managerId: String? = null): AiCapabilityBackground

    fun validateEvaluatorTree(request: SaveEvaluatorTreeRequest): ValidationReport

    fun saveEvaluatorTree(request: SaveEvaluatorTreeRequest): SaveEvaluatorTreeResult
}

/**
 * 能力背景总览：按领域分组的可编排能力。
 * 这是 AI 规划阶段（编排分组 / 构建评估树之前）应首先查阅的"现有能力清单"。
 */
data class AiCapabilityBackground(
    /** 预编码规则（Rule.Coded）：有独立守卫，可使用任意评分效应 */
    val codedRules: List<AiCapabilityEntry>,
    /** 预编码条件（Condition.Plain）：固定常数得分 */
    val plainConditions: List<AiCapabilityEntry>,
    /** 条件树（Condition.Tree）：引用既有条件树配置 */
    val conditionTrees: List<AiCapabilityEntry>,
    /** 正交能力指针：背景阶段只需知道两个 builder 的 sourceId，精细积木见 list_orthogonal_components */
    val orthogonal: AiOrthogonalCapabilityPointer
)

/**
 * 单条能力入口：真实存在的 sourceId + 语义描述 + 该能力需要的属性。
 */
data class AiCapabilityEntry(
    val sourceId: String,
    val name: String?,
    val desc: String?,
    val requiredProperties: List<AiFieldSpec>
)

/**
 * 正交能力指针（背景阶段粗粒度）。
 * 不重复 list_orthogonal_components 的精细类型链路与算子参数，仅给出 builder 的 sourceId 与指引。
 */
data class AiOrthogonalCapabilityPointer(
    val conditionBuilderSourceId: String,
    val ruleBuilderSourceId: String,
    val note: String
)

data class AiFieldSpec(
    val propertyName: String,
    val name: String,
    val description: String,
    val type: String,
    val required: Boolean
)

data class SaveEvaluatorTreeRequest(
    @field:JsonPropertyDescription("评估树的名称，方便人工识别")
    val name: String,
    @field:JsonPropertyDescription("评估树的配置结构体")
    val config: EvaluatorTreeConfig,
    @field:JsonPropertyDescription("评估树的用途或备注描述")
    val description: String? = null,
    @field:JsonPropertyDescription("如果要更新现有配置，请提供现有配置的 ID")
    val existingId: String? = null,
    @field:JsonPropertyDescription("是否启用该配置")
    val enabled: Boolean = true,
    @field:JsonPropertyDescription("所属组的ID")
    val managerId: String? = null
)

data class SaveEvaluatorTreeResult(
    val id: String,
    val validation: ValidationReport
)

data class ValidationReport(
    val ok: Boolean,
    val diagnostics: List<ConfigDiagnostic> = emptyList()
)

data class ConfigDiagnostic(
    val code: String,
    val message: String,
    val path: String? = null
)

/**
 * .cardgroup 卡组文件摘要。
 * fileName 不含 .cardgroup 后缀，enabled 表示该卡组是否启用，cardCount 为卡池卡牌数量。
 */
data class CardGroupSourceInfo(
    val fileName: String,
    val enabled: Boolean,
    val cardCount: Int
)

/**
 * 指定 .cardgroup 文件的完整卡池详情。
 * cards 从 hs.cards 批量查询，text 为卡牌效果描述，可能为 null。
 */
data class CardGroupDetail(
    val fileName: String,
    val cards: List<CardGroupCard>
)

/**
 * 单张卡牌信息（含游戏属性，用于智能分组编排）。
 * cardId 为唯一标识，name 为卡牌名称，text 为卡牌效果描述（可能为 null）。
 * cost/type/attack/health/race/cardClass 来自 hs.cards 数据库，库中无记录时为 null。
 */
data class CardGroupCard(
    val cardId: String,
    val name: String,
    val text: String?,
    val cost: Int? = null,
    val type: String? = null,
    val attack: Int? = null,
    val health: Int? = null,
    val race: String? = null,
    val cardClass: String? = null,
    val weight: Double? = null,
    val changeWeight: Double? = null
)

fun FieldSpec.toAiFieldSpec(): AiFieldSpec {
    return AiFieldSpec(
        propertyName = propertyName,
        name = name,
        description = description,
        type = typeStruct.toAiTypeName(),
        required = constraints.any { it is FieldConstraint.Required }
    )
}

private fun FieldType.toAiTypeName(): String {
    return when (this) {
        FieldType.BooleanType -> "boolean"
        FieldType.DoubleType -> "double"
        FieldType.IntType -> "int"
        is FieldType.ListType -> "list<${elementType.toAiTypeName()}>"
        is FieldType.SelectType -> "select(${dataSourceId}:${valueType.toAiTypeName()})"
        FieldType.StringType -> "string"
    }
}