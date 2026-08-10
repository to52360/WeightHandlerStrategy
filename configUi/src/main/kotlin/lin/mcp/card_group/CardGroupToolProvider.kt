package lin.mcp.card_group

import com.fasterxml.jackson.annotation.JsonPropertyDescription
import lin.mcp.*
import lin.repository.card_group.CardGroupService
import lin.repository.card_group.CardManagerEntity
import lin.rule.tree.CardGroupBinding
import lin.rule.tree.findOverride
import lin.ui.service.TreeConfigService

/**
 * 分组方案域 MCP 工具提供者（card_group / card_group_progress / delete_card_group）。
 * 从原 CardGroupToolProvider 拆分（2026-08-10），按资源域隔离；卡池域见 [CardPoolToolProvider]，
 * 卡组策略保存域见 [SaveCardGroupToolProvider]。
 */
class CardGroupToolProvider(
    private val groupService: CardGroupService,
    private val treeConfigService: TreeConfigService
) : McpToolProvider {

    override fun provide(): List<McpToolHandler> = listOf(
        // ── card_group: 分组方案列表 + 详情 (合并) ──
        typedTool<CardGroupInput>(
            name = "card_group",
            description = "查询卡牌分组方案。支持 action=LIST（列出所有方案的 id/name/sourceFile/enabled/status/description 摘要）和 action=GET（读取某个方案的完整信息，含所有 binding 条目的 id/name/cardIds/stageOverride/conditionalStage）。"
        ) { input ->
            when (val query = input.toQuery()) {
                is CardGroupQuery.ListAction -> mcpSuccess(groupService.loadAllManagers())
                is CardGroupQuery.GetAction -> {
                    val manager = findManager(query.managerId)
                        ?: return@typedTool mcpError("方案不存在: ${query.managerId}")
                    val bindings = groupService.loadBindings(query.managerId).map { bindingView(it) }
                    mcpSuccess(
                        mapOf(
                            "id" to manager.id, "name" to manager.name,
                            "sourceFile" to manager.sourceFile, "enabled" to manager.enabled,
                            "description" to manager.description,
                            "status" to manager.status,
                            "bindings" to bindings
                        )
                    )
                }
            }
        },

        // ── card_group_progress (进度管理: 查询/更新方案级元信息, 二合一) ──
        // 方案 A（2026-08-10）：GET_STATUS/UPDATE_STATUS 二合一，不带覆盖摘要（strategy_coverage 职责分离）；
        // 仅读写 manager 级 description/status，不触碰 bindings。追踪见 architecture-context/config-tooling/。
        typedTool<CardGroupProgressInput>(
            name = "card_group_progress",
            description = """卡组方案进度管理（查询/更新方案级 status 与 description，不触碰 bindings）。
action=GET_STATUS：读取某卡组的 status / description（人工规划跟踪字段），用于恢复配置进度。
action=UPDATE_STATUS：更新 status（PLANNED=已规划未开始 / IN_PROGRESS=配置中 / CONFIGURED=已完成可运行）
与 description（卡组总体描述/规划）。更新缺省（不提供某字段）保持原值。
managerId 由 card_group(action=LIST) 获取。"""
        ) { input ->
            when (val action = input.action.uppercase()) {
                "GET_STATUS" -> {
                    val manager = findManager(input.managerId)
                        ?: return@typedTool mcpError("方案不存在: ${input.managerId}")
                    mcpSuccess(progressView(manager))
                }

                "UPDATE_STATUS" -> {
                    if (input.status.isNullOrBlank() && input.description.isNullOrBlank()) {
                        return@typedTool mcpError("status / description 至少提供一个")
                    }
                    val ok = groupService.updateManagerMeta(input.managerId, input.description, input.status)
                    if (!ok) return@typedTool mcpError("方案不存在: ${input.managerId}")
                    val manager =
                        findManager(input.managerId) ?: return@typedTool mcpError("方案不存在: ${input.managerId}")
                    mcpSuccess(progressView(manager))
                }

                else -> throw McpBadInput("未知 action: $action，支持 GET_STATUS / UPDATE_STATUS")
            }
        },

        // ── delete_card_group ──
        typedTool<DeleteCardGroupInput>(
            name = "delete_card_group",
            description = "删除一个卡牌分组方案及其所有绑定条目和关联的评估树。managerId 由 card_group(action=LIST) 获取。删除不可恢复，返回被删内容清单。"
        ) { input ->
            val allManagers = groupService.loadAllManagers()
            val manager = allManagers.firstOrNull { it.id == input.managerId }
                ?: return@typedTool mcpError("方案不存在: ${input.managerId}")
            val bindings = groupService.loadBindings(input.managerId)
            val bindingNames = bindings.map { it.name }
            val allTreeSummaries = treeConfigService.loadSummaries()
            val linkedTrees = allTreeSummaries.filter { it["managerId"] == input.managerId }
            val treeNames = linkedTrees.map { it["name"] as? String ?: "" }
            linkedTrees.forEach { tree -> treeConfigService.delete(tree["id"] as String) }
            groupService.deleteManager(input.managerId)
            mcpSuccess(
                mapOf(
                    "deleted" to true, "managerId" to input.managerId, "managerName" to manager.name,
                    "deletedBindings" to bindingNames, "deletedTrees" to treeNames,
                    "totalDeleted" to (1 + bindings.size + linkedTrees.size)
                )
            )
        }
    )

    /** 方案查找（多 tool 共用，消除重复的 loadAllManagers 过滤）。 */
    private fun findManager(id: String): CardManagerEntity? =
        groupService.loadAllManagers().firstOrNull { it.id == id }

    /** 进度视图（card_group_progress GET_STATUS / UPDATE_STATUS 返回共用）。 */
    private fun progressView(manager: CardManagerEntity): Map<String, Any?> = mapOf(
        "managerId" to manager.id,
        "managerName" to manager.name,
        "status" to manager.status,
        "description" to manager.description
    )
}

/** 分组展示视图（card_group GET 与 save_card_group 返回共用，消除重复映射）。 */
internal fun bindingView(b: CardGroupBinding): Map<String, Any?> = mapOf(
    "id" to b.id,
    "name" to b.name,
    "description" to b.description,
    "cardIds" to b.cardIds,
    "stageOverride" to b.behaviors.findOverride()?.stageOverride?.name,
    "conditionalStage" to b.behaviors.findOverride()?.conditionalStage?.let { cs ->
        mapOf(
            "conditionId" to cs.conditionId,
            "stage" to cs.stage.name,
            "elseStage" to cs.elseStage?.name
        )
    }
)

private sealed interface CardGroupQuery {
    data object ListAction : CardGroupQuery
    data class GetAction(val managerId: String) : CardGroupQuery
}

private data class CardGroupInput(
    @field:JsonPropertyDescription("操作类型：LIST 列出所有方案摘要，GET 读取方案详情（需传 managerId）")
    val action: String,
    @field:JsonPropertyDescription("方案 id，仅 action=GET 时需要，由 card_group(action=LIST) 返回。")
    val managerId: String? = null
) {
    fun toQuery(): CardGroupQuery = when (action.uppercase()) {
        "GET" -> {
            val managerId = managerId
            if (managerId.isNullOrBlank()) throw McpBadInput("action=GET 需要 managerId 参数") else CardGroupQuery.GetAction(
                managerId
            )
        }

        "LIST" -> CardGroupQuery.ListAction
        else -> throw McpBadInput("未知 action: $action")
    }
}

private data class CardGroupProgressInput(
    @field:JsonPropertyDescription("操作类型：GET_STATUS 读取进度状态，UPDATE_STATUS 更新状态/描述。")
    val action: String,
    @field:JsonPropertyDescription("方案 id，由 card_group(action=LIST) 获取。")
    val managerId: String,
    @field:JsonPropertyDescription("可选（仅 UPDATE_STATUS）：配置进度状态（PLANNED=已规划未开始 / IN_PROGRESS=配置中 / CONFIGURED=已完成可运行）。缺省保持原值不覆盖。")
    val status: String? = null,
    @field:JsonPropertyDescription("可选（仅 UPDATE_STATUS）：卡组总体描述/规划（战术主题、配置目标）。缺省保持原值不覆盖。")
    val description: String? = null
)

private data class DeleteCardGroupInput(
    @field:JsonPropertyDescription("要删除的方案 id，由 card_group(action=LIST) 获取。删除不可恢复。")
    val managerId: String
)
