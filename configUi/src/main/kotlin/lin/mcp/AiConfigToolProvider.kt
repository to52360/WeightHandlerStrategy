package lin.mcp

import com.fasterxml.jackson.databind.ObjectMapper
import lin.ai.config.AiConfigGenerationService
import lin.ai.config.SaveEvaluatorTreeRequest


/**
 * 评估树域 MCP 工具提供者。
 * 负责叶子查询、评估树校验、评估树保存三个工具。
 * 新增评估树相关 tool 只改此文件。
 */
class AiConfigToolProvider(
    private val service: AiConfigGenerationService,
    private val mapper: ObjectMapper
) : McpToolProvider {
    override fun provide(): List<McpToolHandler> = listOf(
        McpToolHandler(
            name = "list_evaluator_leaf_kinds",
            description = "列出 AI 生成评估树可使用的叶子种类（规则、条件、条件树）。",
            inputSchemaJson = """{"type":"object","properties":{}}""",
            call = {
                McpToolResult(mapper.writeValueAsString(service.listEvaluatorLeafKinds()))
            }
        ),
        McpToolHandler(
            name = "validate_evaluator_tree",
            description = "验证一份评估树配置是否满足当前作者侧最小契约，不写入数据库。",
            inputSchemaJson = service.getEvaluatorTreeInputSchema(),
            call = {
                val request = mapper.convertValue(it, SaveEvaluatorTreeRequest::class.java)
                val report = service.validateEvaluatorTree(request)
                McpToolResult(mapper.writeValueAsString(report), isError = !report.ok)
            }
        ),
        McpToolHandler(
            name = "save_evaluator_tree",
            description = "验证并保存评估树配置到 configUi 当前 SQLite 数据库。",
            inputSchemaJson = service.getEvaluatorTreeInputSchema(),
            call = {
                val request = mapper.convertValue(it, SaveEvaluatorTreeRequest::class.java)
                val result = service.saveEvaluatorTree(request)
                McpToolResult(mapper.writeValueAsString(result), isError = !result.validation.ok)
            }
        )
    )
}
