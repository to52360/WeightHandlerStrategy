package lin.mcp

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.fasterxml.jackson.databind.ObjectMapper
import lin.ai.config.toAiFieldSpec
import lin.rule.condition.ConditionPayload
import lin.rule.condition.PipelineAssembler
import lin.rule.orthogonal.TransformCall
import lin.rule.parse.FieldSpec
import lin.rule.score.ScoreEffect
import lin.rule.score.ScoreOperatorRegistry
import lin.rule.tree.EvaluatorLeafConfig
import lin.rule.tree.GuardMissBehavior
import lin.rule.tree.OrthogonalConditionLeafConfig
import lin.rule.tree.OrthogonalRuleLeafConfig

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

    /**
     * 叶子模板序列化器：基于 [mcpMapper]（已注册 EvaluatorLeafConfig 多态 mixin）配 NON_NULL，
     * 省略可选 null 字段，保持示例结构干净。
     */
    private val templateMapper: ObjectMapper = mcpMapper.copy()
        .setSerializationInclusion(JsonInclude.Include.NON_NULL)

    /**
     * 由领域 [EvaluatorLeafConfig] 实例经 [mcpMapper] 多态(WRAPPER_OBJECT)序列化生成叶子模板 Map。
     * 替代手写 JSON 结构，确保模板与领域模型字段同源（单一事实来源），
     * 字段增删/改名由编译器保障同步，而非靠人肉维护 mapOf。
     */
    private fun leafTemplate(instance: EvaluatorLeafConfig): Map<String, Any> {
        val json = templateMapper.writeValueAsString(instance)
        @Suppress("UNCHECKED_CAST")
        return templateMapper.readValue(json, Map::class.java) as Map<String, Any>
    }

    /** 终端算子 DTO 序列化：统一 ConditionOperator 与 ScoreOperator 的展示结构（两者元数据字段一致）。 */
    private fun <T> operatorRows(
        ops: Collection<T>,
        idOf: (T) -> String,
        nameOf: (T) -> String,
        descOf: (T) -> String,
        typeOf: (T) -> String,
        specsOf: (T) -> List<FieldSpec>
    ): List<Map<String, Any>> = ops.map {
        mapOf(
            "id" to idOf(it),
            "name" to nameOf(it),
            "description" to descOf(it),
            "inputType" to typeOf(it),
            "fields" to specsOf(it).map { f -> f.toAiFieldSpec() }
        )
    }

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
                    "description" to "调用 put_draft_leaf 填充叶子节点时，ORTHOGONAL_CONDITION 与 ORTHOGONAL_RULE 的正确 Polymorphic JSON 结构模板。guardMissBehavior 取值：SCORE=守卫未命中时给 missValue 兜底分继续评估（默认）；PRUNE=剪枝终止整棵评估树。",
                    "ORTHOGONAL_CONDITION" to mapOf(
                        "leafConfig" to leafTemplate(
                            OrthogonalConditionLeafConfig(
                                nodeId = "r1",
                                sourceId = "orthogonal_condition",
                                guardCondition = ConditionPayload.PipelineRef(
                                    sourceId = "<DataSourceId>",
                                    transforms = listOf(TransformCall("<TransformId>")),
                                    operatorId = "<OperatorId>",
                                    operatorArgs = mapOf("threshold" to 4),
                                    crossCard = false,
                                    refId = "r1"
                                ),
                                scoreEffect = ScoreEffect.ConstantScore(value = 8.0),
                                args = emptyMap(),
                                guardMissBehavior = GuardMissBehavior.SCORE
                            )
                        )
                    ),
                    "ORTHOGONAL_RULE" to mapOf(
                        "leafConfig" to leafTemplate(
                            OrthogonalRuleLeafConfig(
                                nodeId = "r2",
                                sourceId = "orthogonal_rule",
                                scoreEffect = ScoreEffect.SourceScore(
                                    sourceId = "<DataSourceId>",
                                    operatorId = "<OperatorId>",
                                    operatorArgs = emptyMap(),
                                    crossCard = false,
                                    missValue = 0.0
                                ),
                                args = emptyMap(),
                                guardMissBehavior = GuardMissBehavior.SCORE
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
                    "conditionOperators" to operatorRows(
                        filteredConditionOps,
                        idOf = { it.id },
                        nameOf = { it.name },
                        descOf = { it.description },
                        typeOf = { it.inputType.toString() },
                        specsOf = { it.paramSpecs }
                    )
                ),
                "terminal_score_operators" to mapOf(
                    "description" to "评分终端算子。供 OrthogonalRule 的 ScoreEffect.SourceScore 映射得分使用。",
                    "scoreOperators" to operatorRows(
                        filteredScoreOps,
                        idOf = { it.id },
                        nameOf = { it.name },
                        descOf = { it.description },
                        typeOf = { it.inputType.toString() },
                        specsOf = { it.paramSpecs }
                    )
                )
            )
            mcpSuccess(payload)
        }
    )
}
