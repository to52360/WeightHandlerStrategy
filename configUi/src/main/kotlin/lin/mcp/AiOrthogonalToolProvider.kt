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
import kotlin.reflect.KType
import kotlin.reflect.full.isSubtypeOf

/**
 * 正交组件暴露 MCP 工具提供者。
 * 专管列出系统内可用的 DataSource, Transform, ConditionOperator, ScoreOperator。
 * 基于 Kotlin 强类型 (KType) 动态生成类型链路与兼容拓扑，拒绝硬编码字符串分类。
 *
 * 查询模式（Q-007 定论，2026-08-02）：**按链路分步查询，不一次性全量**。
 * - SOURCES：列出全部 DataSource + leaf 构造模板（链路起点）。
 * - TRANSFORMS（需 sourceId）：列出该源可接的全部 Transform。
 * - OPERATORS（需 sourceId，可选 transformId）：列出管道终点可接的 Operator。
 * 兼容判断一律用 KType `isSubtypeOf`（与提交校验一致），按 sourceId/transformId 精确定位，
 * 不做字符串过滤，不会假阳性也不会残缺视图。
 */
data class ListOrthogonalInput(
    @field:JsonPropertyDescription(
        "查询步骤：SOURCES（默认）=列出全部数据源与 leaf 模板，作为链路起点；" +
                "TRANSFORMS=列出指定数据源可接的全部转换器（需传 sourceId）；" +
                "OPERATORS=列出管道终点可接的算子（需传 sourceId，若管道经过转换器则再传 transformId）。"
    )
    val action: String? = null,

    @field:JsonPropertyDescription("数据源 id。TRANSFORMS / OPERATORS 步骤必填，取自 SOURCES 步骤返回。")
    val sourceId: String? = null,

    @field:JsonPropertyDescription("转换器 id。OPERATORS 步骤可选：管道为 DataSource -> 该 Transform -> Operator 时传，否则视为 DataSource 直出 Operator。")
    val transformId: String? = null,

    @field:JsonPropertyDescription("可选。传 true 时展开 Transform / Operator 的字段级参数细节（fields：参数名/描述/类型/必填），供精确填参时使用。")
    val detail: Boolean? = null
)

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
        expandFields: Boolean,
        idOf: (T) -> String,
        nameOf: (T) -> String,
        descOf: (T) -> String,
        typeOf: (T) -> String,
        specsOf: (T) -> List<FieldSpec>
    ): List<Map<String, Any>> = ops.map {
        val row: MutableMap<String, Any> = mutableMapOf(
            "id" to idOf(it),
            "name" to nameOf(it),
            "description" to descOf(it),
            "inputType" to typeOf(it)
        )
        if (expandFields) row["fields"] = specsOf(it).map { f -> f.toAiFieldSpec() }
        row
    }

    /** 管道终点输出类型的 KType 与描述。 */
    private data class PipelineOutput(val type: KType, val desc: String)

    private fun resolvePipelineOutput(input: ListOrthogonalInput): PipelineOutput {
        val source = input.sourceId?.let { assembler.findDataSource(it) }
            ?: throw McpBadInput("需要有效的 sourceId，请先调 action=SOURCES 获取数据源列表。")
        val transformId = input.transformId
        if (transformId != null) {
            val transform = assembler.findTransform(transformId)
            if (transform == null) throw McpBadInput("transformId 不存在: $transformId")
            if (!source.outputType.isSubtypeOf(transform.inputType)) {
                throw McpBadInput(
                    "管道不兼容：DataSource [${source.id}] output [${source.outputType}] 不是 Transform [$transformId] input [${transform.inputType}] 的子类型。"
                )
            }
            return PipelineOutput(transform.outputType, "${source.id} -> $transformId")
        }
        return PipelineOutput(source.outputType, source.id)
    }

    override fun provide(): List<McpToolHandler> = listOf(
        typedTool<ListOrthogonalInput>(
            name = "list_orthogonal_components",
            description = """
                按链路分步查询正交条件与打分规则的底层积木（DataSources -> Transforms -> Operators），
                兼容判断基于 KType 强类型（与提交校验一致），杜绝不兼容管道。

                三步走：
                1. SOURCES：列出全部数据源与 leaf 构造模板（链路起点）。
                2. TRANSFORMS(sourceId)：列出该数据源可接的全部转换器。
                3. OPERATORS(sourceId[, transformId])：列出管道终点可接的算子
                   （同时给出条件算子 conditionOperators 与评分算子 scoreOperators 两类）。

                组合规则：
                - OrthogonalCondition: DataSource -> Transform(可选) -> ConditionOperator。
                - OrthogonalRule (SourceScore): DataSource -> Transform(可选) -> ScoreOperator。

                需要精确填参时传 `detail=true` 展开 Transform / Operator 的字段级参数（fields）。
            """.trimIndent()
        ) { input ->
            val expandFields = input.detail == true
            val action = input.action?.uppercase() ?: "SOURCES"

            when (action) {
                // ── 第一步：全部数据源 + leaf 模板 ──
                "SOURCES" -> {
                    val payload = mapOf(
                        "dataSources" to assembler.allDataSources().map {
                            mapOf(
                                "id" to it.id,
                                "name" to it.name,
                                "description" to it.description,
                                "outputType" to it.outputType.toString()
                            )
                        },
                        "hint" to "下一步：选定数据源后调用 action=TRANSFORMS 并传 sourceId 查询可接转换器。",
                        "orthogonal_leaf_json_templates" to mapOf(
                            "description" to "调用 put_draft_leaf 填充叶子节点时，ORTHOGONAL_CONDITION 与 ORTHOGONAL_RULE 的正确 Polymorphic JSON 结构模板。guardMissBehavior 取值：SCORE=守卫未命中时给 missValue 兜底费值继续评估（默认，missValue=0 即不参与计费）；PRUNE=守卫未命中即门控短路整棵 AND/OR 子树（典型：AND 内多条件并列门控，任一条件不满足整棵不加分）；BAN=守卫未命中即强制禁止该卡打出（整卡不可用，如手牌超上限禁止过牌卡）。",
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
                                        guardCondition = ConditionPayload.PipelineRef(
                                            sourceId = "<DataSourceId>",
                                            transforms = listOf(TransformCall("<TransformId>")),
                                            operatorId = "<OperatorId>",
                                            operatorArgs = mapOf("threshold" to 4),
                                            crossCard = false,
                                            refId = "r2_guard"
                                        ),
                                        scoreEffect = ScoreEffect.SourceScore(
                                            sourceId = "<DataSourceId>",
                                            operatorId = "<OperatorId>",
                                            operatorArgs = emptyMap(),
                                            crossCard = false,
                                            missValue = 0.0
                                        ),
                                        args = emptyMap(),
                                        guardMissBehavior = GuardMissBehavior.PRUNE
                                    )
                                )
                            ),
                            "crossCard_instruction" to "crossCard (Boolean, 默认 false): 只有对于不依赖评估目标手牌(callCard)的全局事件或局势管道，填入 true 才能在同回合多手牌评估时启用评估级内容哈希缓存复用。"
                        )
                    )
                    mcpSuccess(payload)
                }

                // ── 第二步：指定数据源可接的全部转换器 ──
                "TRANSFORMS" -> {
                    val source = input.sourceId?.let { assembler.findDataSource(it) }
                        ?: return@typedTool mcpError("action=TRANSFORMS 需要有效的 sourceId，请先调 action=SOURCES 获取数据源列表。")
                    val compatible = assembler.allTransforms().filter { t ->
                        source.outputType.isSubtypeOf(t.inputType)
                    }
                    mcpSuccess(
                        mapOf(
                            "sourceId" to source.id,
                            "sourceOutputType" to source.outputType.toString(),
                            "transforms" to compatible.map {
                                val row: MutableMap<String, Any> = mutableMapOf(
                                    "id" to it.id,
                                    "name" to it.name,
                                    "description" to it.description,
                                    "inputType" to it.inputType.toString(),
                                    "outputType" to it.outputType.toString()
                                )
                                if (expandFields) row["fields"] = it.fields.map { f -> f.toAiFieldSpec() }
                                row
                            },
                            "hint" to "选定转换器后调用 action=OPERATORS 并传 sourceId + transformId 查询管道终点可接算子；若无合适转换器，可直接用 sourceId 查 OPERATORS（数据源直出）。"
                        )
                    )
                }

                // ── 第三步：管道终点可接的算子（条件 + 评分两类） ──
                "OPERATORS" -> {
                    val output = try {
                        resolvePipelineOutput(input)
                    } catch (e: McpBadInput) {
                        return@typedTool mcpError(e.message ?: "管道定位失败")
                    }
                    val compatibleConditionOps = assembler.allOperators().filter { op ->
                        output.type.isSubtypeOf(op.inputType)
                    }
                    val compatibleScoreOps = scoreOperatorRegistry.all().filter { op ->
                        output.type.isSubtypeOf(op.inputType)
                    }
                    mcpSuccess(
                        mapOf(
                            "pipeline" to output.desc,
                            "pipelineOutputType" to output.type.toString(),
                            "conditionOperators" to operatorRows(
                                compatibleConditionOps,
                                expandFields = expandFields,
                                idOf = { it.id },
                                nameOf = { it.name },
                                descOf = { it.description },
                                typeOf = { it.inputType.toString() },
                                specsOf = { it.paramSpecs }
                            ),
                            "scoreOperators" to operatorRows(
                                compatibleScoreOps,
                                expandFields = expandFields,
                                idOf = { it.id },
                                nameOf = { it.name },
                                descOf = { it.description },
                                typeOf = { it.inputType.toString() },
                                specsOf = { it.paramSpecs }
                            )
                        )
                    )
                }

                else -> mcpError("未知 action: $action，支持 SOURCES / TRANSFORMS / OPERATORS")
            }
        }
    )
}
