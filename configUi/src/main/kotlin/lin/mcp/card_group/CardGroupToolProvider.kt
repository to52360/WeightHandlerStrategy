package lin.mcp.card_group

import com.fasterxml.jackson.annotation.JsonPropertyDescription
import lin.mcp.*
import lin.mcp.action.*
import lin.repository.card_group.CardGroupService
import lin.repository.card_group.CardManagerEntity
import lin.rule.tree.CardGroupBinding
import lin.rule.tree.findOverride
import lin.rule.tree.findSurplusGate
import lin.ui.service.TreeConfigService

/**
 * 分组方案域 MCP 工具提供者（写工具 + 动作同文件）：
 * - [CardGroupAction]：resource=card_group 的 get/list/delete（原 card_group 查询 + delete_card_group 工具）。
 * - provide()：card_group_progress 进度管理（D-006 边界保留独立）。
 * 卡池域见 [CardPoolToolProvider]，卡组策略保存域见 [SaveCardGroupToolProvider]。
 */
class CardGroupToolProvider(
    private val groupService: CardGroupService,
    private val treeConfigService: TreeConfigService
) : McpToolProvider {

    override val actions: List<ResourceAction> = listOf(
        CardGroupAction(groupService, treeConfigService)
    )

    override fun provide(): List<McpToolHandler> = listOf(
        // ── card_group_progress (进度管理: 查询/更新方案级元信息, 二合一, D-006 保留独立) ──
        typedTool<CardGroupProgressInput>(
            name = "card_group_progress",
            description = """卡组方案进度管理（查询/更新方案级 status 与 description，不触碰 bindings）。
action=GET_STATUS：读取某卡组的 status / description（人工规划跟踪字段），用于恢复配置进度。
action=UPDATE_STATUS：更新 status（PLANNED=已规划未开始 / IN_PROGRESS=配置中 / CONFIGURED=已完成可运行）
与 description（卡组总体描述/规划）。更新缺省（不提供某字段）保持原值。
managerId 由 list(resource=card_group) 获取。"""
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

    // ── 动作：card_group get/list/delete ──

    private class CardGroupAction(
        private val groupService: CardGroupService,
        private val treeConfigService: TreeConfigService
    ) : GetAction, ListAction, DeleteAction {

        override val resource: String = ActionResources.CARD_GROUP

        override fun handleList(managerId: String?): McpToolResult {
            return mcpSuccess(groupService.loadAllManagers())
        }

        override fun handleGet(id: String): McpToolResult {
            val manager = groupService.loadAllManagers().firstOrNull { it.id == id }
                ?: return mcpError("方案不存在: $id")
            val bindings = groupService.loadBindings(id).map { bindingView(it) }
            return mcpSuccess(
                mapOf(
                    "id" to manager.id, "name" to manager.name,
                    "sourceFile" to manager.sourceFile, "enabled" to manager.enabled,
                    "description" to manager.description,
                    "status" to manager.status,
                    "bindings" to bindings
                )
            )
        }

        override val getFieldHint: String = "卡组方案 id（managerId，由 list(resource=card_group) 返回）"

        override fun handleDelete(id: String): McpToolResult {
            val manager = groupService.loadAllManagers().firstOrNull { it.id == id }
                ?: return mcpError("方案不存在: $id")
            val bindings = groupService.loadBindings(id)
            val bindingNames = bindings.map { it.name }
            val allTreeSummaries = treeConfigService.loadSummaries()
            val linkedTrees = allTreeSummaries.filter { it["managerId"] == id }
            val treeNames = linkedTrees.map { it["name"] as? String ?: "" }
            linkedTrees.forEach { tree -> treeConfigService.delete(tree["id"] as String) }
            groupService.deleteManager(id)
            return mcpSuccess(
                mapOf(
                    "deleted" to true, "managerId" to id, "managerName" to manager.name,
                    "deletedBindings" to bindingNames, "deletedTrees" to treeNames,
                    "totalDeleted" to (1 + bindings.size + linkedTrees.size)
                )
            )
        }

        override val deleteFieldHint: String = "卡组方案 id（managerId，由 list(resource=card_group) 返回）"

        override val deleteSemantics: String = "级联删除：绑定条目 + 关联评估树一并删除，不可恢复"
    }
}

/** 分组展示视图（card_group get 与 save_card_group 返回共用，消除重复映射）。 */
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
    },
    "surplusIdleThreshold" to b.behaviors.findSurplusGate()?.idleThreshold
)

private data class CardGroupProgressInput(
    @field:JsonPropertyDescription("操作类型：GET_STATUS 读取进度状态，UPDATE_STATUS 更新状态/描述。")
    val action: String,
    @field:JsonPropertyDescription("方案 id，由 list(resource=card_group) 获取。")
    val managerId: String,
    @field:JsonPropertyDescription("可选（仅 UPDATE_STATUS）：配置进度状态（PLANNED=已规划未开始 / IN_PROGRESS=配置中 / CONFIGURED=已完成可运行）。缺省保持原值不覆盖。")
    val status: String? = null,
    @field:JsonPropertyDescription("可选（仅 UPDATE_STATUS）：卡组总体描述/规划（战术主题、配置目标）。缺省保持原值不覆盖。")
    val description: String? = null
)
