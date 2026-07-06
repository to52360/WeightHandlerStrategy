package lin.mcp

import com.fasterxml.jackson.databind.ObjectMapper
import lin.ai.config.toAiFieldSpec
import lin.rule.condition.PipelineAssembler
import lin.rule.score.ScoreOperatorRegistry

/**
 * 正交组件暴露 MCP 工具提供者。
 * 专管列出系统内可用的 DataSource, Transform, ConditionOperator, ScoreOperator。
 */
class AiOrthogonalToolProvider(
    private val assembler: PipelineAssembler,
    private val scoreOperatorRegistry: ScoreOperatorRegistry,
    private val mapper: ObjectMapper
) : McpToolProvider {
    override fun provide(): List<McpToolHandler> = listOf(
        McpToolHandler(
            name = "list_orthogonal_components",
            description = """
                列出正交条件的底层积木。分为 shared_pipeline_nodes 和 terminal_operators 两个域。
                注意：
                1. 组装 OrthogonalCondition（作为独立的条件或 Rule 的 guard）时，组合顺序是 DataSources -> Transforms(可选) -> ConditionOperators，填入 PipelineRef。
                2. 组装 OrthogonalRule 的 ScoreEffect.SourceScore 时，组合顺序是 DataSources -> Transforms(可选) -> ScoreOperators。
            """.trimIndent(),
            inputSchemaJson = """{"type":"object","properties":{}}""",
            call = {
                val payload = mapOf(
                    "shared_pipeline_nodes" to mapOf(
                        "description" to "条件管道和打分管道共用的前置数据提供节点。DataSource 用于指定 sourceId，Transforms 用于数据加工。",
                        "dataSources" to assembler.allDataSources().map {
                            mapOf("id" to it.id, "description" to it.description)
                        },
                        "transforms" to assembler.allTransforms().map {
                            mapOf(
                                "id" to it.id,
                                "description" to it.description,
                                "fields" to it.fields.map { f -> f.toAiFieldSpec() })
                        }
                    ),
                    "terminal_condition_operators" to mapOf(
                        "description" to "条件终端算子。仅供 OrthogonalCondition 结尾进行 boolean 判断时填入 operatorId/operatorArgs。",
                        "conditionOperators" to assembler.allOperators().map {
                            mapOf(
                                "id" to it.id,
                                "description" to it.description,
                                "fields" to it.paramSpecs.map { f -> f.toAiFieldSpec() })
                        }
                    ),
                    "terminal_score_operators" to mapOf(
                        "description" to "评分终端算子。仅供 OrthogonalRule 的 ScoreEffect.SourceScore 打分时填入 operatorId/operatorArgs。",
                        "scoreOperators" to scoreOperatorRegistry.all().map {
                            mapOf(
                                "id" to it.id,
                                "description" to it.description,
                                "fields" to it.paramSpecs.map { f -> f.toAiFieldSpec() })
                        }
                    )
                )
                McpToolResult(mapper.writeValueAsString(payload))
            }
        )
    )
}
