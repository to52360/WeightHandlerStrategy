package lin.mcp

import com.fasterxml.jackson.annotation.JsonPropertyDescription
import lin.ai.config.AiConfigGenerationService
import lin.repository.combo_plan.ComboPlanDefinitionRepository
import lin.ui.service.TreeConfigService

/**
 * 评估树配置 MCP 工具提供者。
 * 专管能力背景、评估树查询与删除。
 */
class AiTreeConfigToolProvider(
    private val service: AiConfigGenerationService,
    private val treeConfigService: TreeConfigService,
    private val comboPlanDefinitionRepository: ComboPlanDefinitionRepository
) : McpToolProvider {
    override fun provide(): List<McpToolHandler> = listOf(
        McpToolHandler(
            name = "list_capability_background",
            description = """
                【能力背景 / 规划前置】列出系统当前真实存在的全部可编排能力，按领域分组：
                codedRules（预编码规则）、plainConditions（预编码条件）、conditionTrees（条件树），
                每项含 sourceId、name、desc 以及该能力需要的属性 requiredProperties。
                AI 必须在编排卡牌分组、构建评估树之前先调用本工具，依据真实存在的 sourceId 与属性来规划，
                严禁凭空捏造规则/条件 ID 或属性字段，否则会在提交时被校验拒绝（幻觉）。
                正交能力（orthogonal_condition / orthogonal_rule）的底层积木与类型链路不在此展开，
                构造正交叶子时再调用 list_orthogonal_components 获取精细细节。
            """.trimIndent(),
            inputSchemaJson = """{"type":"object","properties":{}}""",
            call = {
                mcpSuccess(service.listCapabilityBackground())
            }
        ),

        // ── evaluator_tree: 评估树列表 + 详情 (合并) ──
        typedTool<EvaluatorTreeInput>(
            name = "evaluator_tree",
            description = "查询评估树。支持 action=LIST（列出所有已保存树的 id/name/bindingType 摘要）和 action=GET（读取某棵树的完整配置：managerId/bindingType/bindingIds/tree 拓扑/leafConfigs/associatedComboPlans）。"
        ) { input ->
            when (input.action.uppercase()) {
                "LIST" -> {
                    val summaries = treeConfigService.loadSummaries()
                    mcpSuccess(summaries)
                }
                "GET" -> {
                    if (input.id.isNullOrBlank()) {
                        mcpError("action=GET 需要 id 参数")
                    } else {
                        val result = treeConfigService.findById(input.id)
                        if (result?.second == null) {
                            mcpError("树配置不存在")
                        } else {
                            val entity = result.first!!
                            val config = result.second!!
                            val managerId = entity.managerId
                            val associatedComboPlans = if (!managerId.isNullOrBlank()) {
                                comboPlanDefinitionRepository.findByManagerId(managerId).map { plan ->
                                    mapOf(
                                        "id" to plan.id,
                                        "relation" to plan.relation,
                                        "score" to plan.score,
                                        "coreGroupIds" to plan.coreGroupIdSet().toList(),
                                        "depGroupIds" to plan.depGroupIdSet().toList(),
                                        "coreMutex" to plan.coreMutex,
                                        "mustAdjacent" to plan.mustAdjacent
                                    )
                                }
                            } else emptyList()

                            mcpSuccess(mapOf(
                                "managerId" to managerId,
                                "bindingType" to config.bindingType.name,
                                "bindingIds" to config.bindingIds,
                                "tree" to config.root.toNamed(),
                                "leafConfigs" to config.leafConfigs,
                                "associatedComboPlans" to associatedComboPlans
                            ))
                        }
                    }
                }
                else -> mcpError("未知 action: ${input.action}，支持 LIST / GET")
            }
        },

        // ── delete_evaluator_tree (保留) ──
        typedTool<DeleteTreeInput>(
            name = "delete_evaluator_tree",
            description = "删除一棵已保存的评估树（含其叶子配置）。treeId 由 evaluator_tree(action=LIST) 获取。删除不可恢复。无效 ID 返回错误+现有树列表以防幻觉。"
        ) { input ->
            val summaries = treeConfigService.loadSummaries()
            val target = summaries.firstOrNull { it["id"] == input.treeId }
                ?: return@typedTool mcpError("树不存在: ${input.treeId}。当前存在的树列表: ${summaries.map { mapOf("id" to it["id"], "name" to it["name"]) }}")
            treeConfigService.delete(input.treeId)
            mcpSuccess(mapOf("deleted" to true, "treeId" to input.treeId, "treeName" to target["name"]))
        }
    )
}

private data class EvaluatorTreeInput(
    @field:JsonPropertyDescription("操作类型：LIST 列出所有树摘要，GET 读取树完整配置（需传 id）")
    val action: String,
    @field:JsonPropertyDescription("树 id，仅 action=GET 时需要，由 evaluator_tree(action=LIST) 返回。")
    val id: String? = null
)
