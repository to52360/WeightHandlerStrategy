package lin.mcp

import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.fasterxml.jackson.databind.ObjectMapper
import lin.ai.config.toAiFieldSpec
import lin.rule.condition.PipelineAssembler
import lin.rule.score.ScoreOperatorRegistry

data class ListOrthogonalInput(
    @field:JsonPropertyDescription("可选。按输出类型精准过滤数据源或转换器（如 'Int'、'Card'、'WarView'、'ComboCard'）")
    val targetOutputType: String? = null,

    @field:JsonPropertyDescription("可选。按输入类型精准过滤转换器或判定算子（如 'List'、'Int'、'WarView'、'Card'）")
    val targetInputType: String? = null
)

/**
 * 正交组件暴露 MCP 工具提供者。
 * 专管列出系统内可用的 DataSource, Transform, ConditionOperator, ScoreOperator。
 * 基于 Kotlin 强类型 (KType) 动态生成类型链路与兼容拓扑图，拒绝硬编码字符串分类。
 */
class AiOrthogonalToolProvider(
    private val assembler: PipelineAssembler,
    private val scoreOperatorRegistry: ScoreOperatorRegistry
) : McpToolProvider {
    override fun provide(): List<McpToolHandler> = listOf(
        typedTool<ListOrthogonalInput>(
            name = "list_orthogonal_components",
            description = """
                列出正交条件与打分规则的底层积木（DataSources, Transforms, ConditionOperators, ScoreOperators）。
                支持按 `targetOutputType` / `targetInputType` 可选类型精准过滤，并提供动态生成的 `type_compatibility_map` 类型流向拓扑。

                组合规则：
                1. OrthogonalCondition: DataSources -> Transforms(可选) -> ConditionOperators（填入 PipelineRef）。
                2. OrthogonalRule (SourceScore): DataSources -> Transforms(可选) -> ScoreOperators。

                【类型链路强兼容】前一个节点的 outputType 必须兼容后一个节点的 inputType，否则提交流程会被 PIPELINE_TYPE_MISMATCH 拒绝。
                例如：DataSource「hand_cards」(output=List<Card>) -> Transform「count_projection」(input=List<*>, output=Int) -> Operator「gte」(input=Int)。
            """.trimIndent()
        ) { input ->
            val targetOut = input.targetOutputType
            val targetIn = input.targetInputType

            val allDataSources = assembler.allDataSources()
            val allTransforms = assembler.allTransforms()
            val allOperators = assembler.allOperators()
            val allScoreOps = scoreOperatorRegistry.all()

            // 筛选节点（先过滤，再构建兼容表，避免全量返回冗余数据）
            val filteredDataSources = if (targetOut.isNullOrBlank()) allDataSources else allDataSources.filter {
                it.outputType.toString().contains(targetOut, ignoreCase = true)
            }

            val filteredTransforms = allTransforms.filter { t ->
                (targetOut.isNullOrBlank() || t.outputType.toString().contains(targetOut, ignoreCase = true)) &&
                        (targetIn.isNullOrBlank() || t.inputType.toString().contains(targetIn, ignoreCase = true))
            }

            val filteredConditionOps = if (targetIn.isNullOrBlank()) allOperators else allOperators.filter {
                it.inputType.toString().contains(targetIn, ignoreCase = true)
            }

            val filteredScoreOps = if (targetIn.isNullOrBlank()) allScoreOps else allScoreOps.filter {
                it.inputType.toString().contains(targetIn, ignoreCase = true)
            }

            // 动态构建基于 KType 的类型兼容表（只为过滤后的 DataSource 构建，兼容列表也仅含过滤后的 Transform/Operator）
            val typeCompatibilityMap = filteredDataSources.associate { ds ->
                val dsOutStr = ds.outputType.toString()
                val dsSimpleType = dsOutStr.substringAfterLast('.')

                val compatibleTransforms = filteredTransforms.filter { t ->
                    val inStr = t.inputType.toString()
                    inStr.contains(dsSimpleType, ignoreCase = true) || dsOutStr.contains(
                        inStr.substringAfterLast('.'),
                        ignoreCase = true
                    )
                }.map { it.id }

                val compatibleOps = filteredConditionOps.filter { op ->
                    val inStr = op.inputType.toString()
                    inStr.contains(dsSimpleType, ignoreCase = true) || dsOutStr.contains(
                        inStr.substringAfterLast('.'),
                        ignoreCase = true
                    )
                }.map { it.id }

                ds.id to mapOf(
                    "outputType" to dsOutStr,
                    "compatibleTransforms" to compatibleTransforms,
                    "directOperators" to compatibleOps
                )
            }

            val payload = mapOf(
                "orthogonal_leaf_json_templates" to mapOf(
                    "description" to "调用 put_draft_leaf 填充叶子节点时，ORTHOGONAL_CONDITION 与 ORTHOGONAL_RULE 的正确 Polymorphic JSON 结构模板",
                    "ORTHOGONAL_CONDITION" to mapOf(
                        "leafConfig" to mapOf(
                            "ORTHOGONAL_CONDITION" to mapOf(
                                "nodeId" to "r1",
                                "sourceId" to "orthogonal_condition",
                                "guardCondition" to mapOf(
                                    "PipelineRef" to mapOf(
                                        "sourceId" to "<DataSourceId>",
                                        "transforms" to listOf(
                                            mapOf(
                                                "transformId" to "<TransformId>",
                                                "args" to emptyMap<String, Any>()
                                            )
                                        ),
                                        "operatorId" to "<OperatorId>",
                                        "operatorArgs" to mapOf("threshold" to 4),
                                        "crossCard" to false,
                                        "refId" to "r1"
                                    )
                                ),
                                "scoreEffect" to mapOf("ConstantScore" to mapOf("value" to 8.0)),
                                "args" to emptyMap<String, Any>(),
                                "guardMissBehavior" to "SCORE"
                            )
                        )
                    ),
                    "ORTHOGONAL_RULE" to mapOf(
                        "leafConfig" to mapOf(
                            "ORTHOGONAL_RULE" to mapOf(
                                "nodeId" to "r2",
                                "sourceId" to "orthogonal_rule",
                                "scoreEffect" to mapOf(
                                    "SourceScore" to mapOf(
                                        "sourceId" to "<DataSourceId>",
                                        "operatorId" to "<OperatorId>",
                                        "operatorArgs" to emptyMap<String, Any>(),
                                        "crossCard" to false,
                                        "missValue" to 0.0
                                    )
                                ),
                                "args" to emptyMap<String, Any>(),
                                "guardMissBehavior" to "SCORE"
                            )
                        )
                    ),
                    "crossCard_instruction" to "crossCard (Boolean, 默认 false): 只有对于不依赖评估目标手牌(callCard)的全局事件或局势管道，填入 true 才能在同回合多手牌评估时启用评估级内容哈希缓存复用。"
                ),
                "type_compatibility_map" to typeCompatibilityMap,
                "shared_pipeline_nodes" to mapOf(
                    "description" to "数据源与转换器节点。根据 inputType / outputType 严格进行数据流衔接。",
                    "dataSources" to filteredDataSources.map {
                        mapOf(
                            "id" to it.id,
                            "name" to it.name,
                            "description" to it.description,
                            "outputType" to it.outputType.toString()
                        )
                    },
                    "transforms" to filteredTransforms.map {
                        mapOf(
                            "id" to it.id,
                            "name" to it.name,
                            "description" to it.description,
                            "inputType" to it.inputType.toString(),
                            "outputType" to it.outputType.toString(),
                            "fields" to it.fields.map { f -> f.toAiFieldSpec() }
                        )
                    }
                ),
                "terminal_condition_operators" to mapOf(
                    "description" to "条件终端算子。供 OrthogonalCondition 结尾布尔判定使用。",
                    "conditionOperators" to filteredConditionOps.map {
                        mapOf(
                            "id" to it.id,
                            "name" to it.name,
                            "description" to it.description,
                            "inputType" to it.inputType.toString(),
                            "fields" to it.paramSpecs.map { f -> f.toAiFieldSpec() }
                        )
                    }
                ),
                "terminal_score_operators" to mapOf(
                    "description" to "评分终端算子。供 OrthogonalRule 的 ScoreEffect.SourceScore 映射得分使用。",
                    "scoreOperators" to filteredScoreOps.map {
                        mapOf(
                            "id" to it.id,
                            "name" to it.name,
                            "description" to it.description,
                            "inputType" to it.inputType.toString(),
                            "fields" to it.paramSpecs.map { f -> f.toAiFieldSpec() }
                        )
                    }
                )
            )
            mcpSuccess(payload)
        }
    )
}
