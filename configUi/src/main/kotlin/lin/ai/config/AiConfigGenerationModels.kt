package lin.ai.config

import com.fasterxml.jackson.annotation.JsonPropertyDescription
import lin.rule.parse.FieldConstraint
import lin.rule.parse.FieldSpec
import lin.rule.parse.FieldType
import lin.rule.tree.EvaluatorLeafKind
import lin.rule.tree.EvaluatorTreeConfig

/**
 * AI 配置生成的最小业务入口。
 * MCP 只负责协议适配，本接口负责暴露"AI 需要知道什么、提交什么、得到什么反馈"。
 * 仅聚焦评估树领域（叶子查询 / 校验 / 保存），卡池查询已拆到 CardGroupQueryService。
 */
interface AiConfigGenerationService {
    fun listEvaluatorLeafKinds(): List<AiEvaluatorLeafKind>

    fun validateEvaluatorTree(request: SaveEvaluatorTreeRequest): ValidationReport

    fun saveEvaluatorTree(request: SaveEvaluatorTreeRequest): SaveEvaluatorTreeResult
}

data class AiEvaluatorLeafKind(
    val kind: EvaluatorLeafKind,
    val sourceId: String,
    val name: String?,
    val desc: String?,
    val fields: List<AiFieldSpec>
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
 * 单张卡牌信息。
 * cardId 为唯一标识，name 为卡牌名称，text 为卡牌效果描述（可能为 null）。
 */
data class CardGroupCard(
    val cardId: String,
    val name: String,
    val text: String?
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