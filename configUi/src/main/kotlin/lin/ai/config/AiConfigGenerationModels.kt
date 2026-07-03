package lin.ai.config

import lin.rule.parse.FieldConstraint
import lin.rule.parse.FieldSpec
import lin.rule.parse.FieldType
import lin.rule.tree.EvaluatorLeafKind
import lin.rule.tree.EvaluatorTreeConfig

/**
 * AI 配置生成的最小业务入口。
 * MCP 只负责协议适配，本接口负责暴露“AI 需要知道什么、提交什么、得到什么反馈”。
 */
interface AiConfigGenerationService {
    fun listEvaluatorLeafKinds(): List<AiEvaluatorLeafKind>

    fun validateEvaluatorTree(request: SaveEvaluatorTreeRequest): ValidationReport

    fun saveEvaluatorTree(request: SaveEvaluatorTreeRequest): SaveEvaluatorTreeResult

    fun getEvaluatorTreeInputSchema(): String
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
    val name: String,
    val config: EvaluatorTreeConfig,
    val description: String? = null,
    val existingId: String? = null,
    val enabled: Boolean = true,
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
