package lin.mcp

import com.fasterxml.jackson.databind.ObjectMapper
import lin.ai.config.AiConfigGenerationService
import lin.ai.config.SaveEvaluatorTreeRequest

/**
 * MCP tools 到项目业务服务的路由层。
 * 这里故意不放 AI prompt，也不直接访问 SQLite，避免协议层吞掉业务边界。
 */
class McpToolRouter(
    private val aiConfigGenerationService: AiConfigGenerationService,
    private val mapper: ObjectMapper
) {
    fun tools(): List<McpToolHandler> = listOf(
        ListEvaluatorLeafKindsTool(),
        ValidateEvaluatorTreeTool(),
        SaveEvaluatorTreeTool()
    )

    private inner class ListEvaluatorLeafKindsTool : McpToolHandler {
        override val name: String = "list_evaluator_leaf_kinds"
        override val description: String = "列出 AI 生成评估树可使用的叶子种类（规则、条件、条件树）。"
        override val inputSchemaJson: String = """{"type":"object","properties":{}}"""

        override fun call(arguments: Map<String, Any?>): McpToolResult {
            return jsonResult(aiConfigGenerationService.listEvaluatorLeafKinds())
        }
    }

    private inner class ValidateEvaluatorTreeTool : McpToolHandler {
        override val name: String = "validate_evaluator_tree"
        override val description: String = "验证一份评估树配置是否满足当前作者侧最小契约，不写入数据库。"
        override val inputSchemaJson: String = saveEvaluatorTreeSchema()

        override fun call(arguments: Map<String, Any?>): McpToolResult {
            val request = mapper.convertValue(arguments, SaveEvaluatorTreeRequest::class.java)
            return jsonResult(aiConfigGenerationService.validateEvaluatorTree(request))
        }
    }

    private inner class SaveEvaluatorTreeTool : McpToolHandler {
        override val name: String = "save_evaluator_tree"
        override val description: String = "验证并保存评估树配置到 configUi 当前 SQLite 数据库。"
        override val inputSchemaJson: String = saveEvaluatorTreeSchema()

        override fun call(arguments: Map<String, Any?>): McpToolResult {
            val request = mapper.convertValue(arguments, SaveEvaluatorTreeRequest::class.java)
            val result = aiConfigGenerationService.saveEvaluatorTree(request)
            return jsonResult(result, isError = !result.validation.ok)
        }
    }

    private fun jsonResult(value: Any, isError: Boolean = false): McpToolResult {
        return McpToolResult(contentJson = mapper.writeValueAsString(value), isError = isError)
    }

    private fun saveEvaluatorTreeSchema(): String {
        // ARCH-PLACEHOLDER(ai-config-generator, P-002): MCP inputSchema 暂用宽松 object，未展开 EvaluatorTreeConfig JSON Schema | replace-with: 根据 EvaluatorNode/EvaluatorPayload 生成或手写最小 JSON Schema
        return """
            {
              "type": "object",
              "required": ["name", "config"],
              "properties": {
                "name": { "type": "string" },
                "existingId": { "type": "string" },
                "enabled": { "type": "boolean" },
                "managerId": { "type": "string" },
                "config": { "type": "object" }
              }
            }
        """.trimIndent()
    }
}
