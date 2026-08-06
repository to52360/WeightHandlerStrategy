package lin.mcp

import com.fasterxml.jackson.annotation.JsonPropertyDescription
import lin.repository.aura_boost.AuraBoostConfigService
import lin.repository.aura_boost.AuraBoostEntity
import lin.repository.aura_boost.SaveAuraBoostInput
import lin.repository.condition_tree.ConditionTreeConfigService
import lin.repository.condition_tree.createConditionTreeConfigMapper

/**
 * Push 广播评分配置（aura-boost）MCP 工具提供者。
 *
 * AuraBoost = 触发条件树（conditionId，全局检测）命中后，给 targetConditionId（受益卡过滤）命中的卡加分。
 * additive 独立通道：命中分与评估树分相加；光环加分只走 AuraBoost，评估树不写光环条件（D-004）。
 * managerId 为消费方归属（卡组级配置），引用的条件树是全局资源（D-003）。
 *
 * 内联创建（Q-003）：conditionId/targetConditionId 与 conditionTreeJson/targetConditionTreeJson 互斥，
 * 提供 treeJson 时自动创建条件树返回新 id，一次性树无需先建模板。
 */
class AuraBoostToolProvider(
    private val service: AuraBoostConfigService,
    private val conditionTreeService: ConditionTreeConfigService
) : McpToolProvider {

    private val mapper = createConditionTreeConfigMapper()

    override fun provide(): List<McpToolHandler> = listOf(
        typedTool<AuraBoostQueryInput>(
            name = "aura_boost",
            description = """
                查询 Push 广播评分配置（光环/全局条件加分）。支持 action=LIST（列出全部 AuraBoost 摘要，
                可传 managerId 按卡组过滤）和 action=GET（读取单条完整配置：触发条件树 id、受益过滤条件树 id、加分）。
            """.trimIndent()
        ) { input ->
            when (val query = input.toQuery()) {
                is AuraBoostQuery.List -> {
                    val list = service.loadAll()
                        .filter { query.managerId == null || it.managerId == query.managerId }
                    mcpSuccess(list.map { it.toSummary() })
                }

                is AuraBoostQuery.Get -> {
                    val entity = service.findById(query.id)
                        ?: return@typedTool mcpError("AuraBoost 不存在: ${query.id}")
                    mcpSuccess(entity.toSummary())
                }
            }
        },

        typedTool<SaveAuraBoostMcpInput>(
            name = "save_aura_boost",
            description = """
                创建或更新一条 Push 广播评分配置（AuraBoost）。
                语义：触发条件树命中（如"莱妮莎在场"）→ 给受益过滤条件树命中的卡 +score。
                additive 通道：加分与评估树分相加，光环加分只走 AuraBoost，评估树不写光环条件（防双倍计分）。

                【条件树两种提供方式（二选一，互斥）】
                - 复用已有条件树：conditionId / targetConditionId 传已有树 id（来自 condition_tree(action=LIST)）
                - 一次性内联创建：conditionTreeJson / targetConditionTreeJson 直接传条件树 JSON（{id,name,root}），
                  无需先 condition_tree(action=SAVE) 建模板，本工具自动建树并返回新 id

                managerId 关联卡组（消费方归属）；引用的条件树是全局资源。传 existingId 更新已有配置。
            """.trimIndent()
        ) { input ->
            val conditionId = resolveConditionTreeReference(
                service = conditionTreeService,
                mapper = mapper,
                conditionId = input.conditionId,
                treeJson = input.conditionTreeJson,
                defaultName = "${input.name ?: "boost"}_trigger",
                label = "触发条件树",
                managerId = input.managerId
            )
            val targetConditionId = resolveConditionTreeReference(
                service = conditionTreeService,
                mapper = mapper,
                conditionId = input.targetConditionId,
                treeJson = input.targetConditionTreeJson,
                defaultName = "${input.name ?: "boost"}_target",
                label = "受益过滤条件树",
                managerId = input.managerId
            )
            val id = service.save(
                SaveAuraBoostInput(
                    name = input.name,
                    conditionId = conditionId,
                    targetConditionId = targetConditionId,
                    score = input.score,
                    managerId = input.managerId,
                    existingId = input.existingId
                )
            )
            mcpSuccess(
                mapOf(
                    "id" to id, "name" to input.name, "score" to input.score,
                    "conditionId" to conditionId, "targetConditionId" to targetConditionId
                )
            )
        },

        typedTool<DeleteAuraBoostInput>(
            name = "delete_aura_boost",
            description = "删除一条 Push 广播评分配置（AuraBoost）。boostId 由 aura_boost(action=LIST) 获取。删除不可恢复。"
        ) { input ->
            val entity = service.findById(input.boostId)
                ?: return@typedTool mcpError("AuraBoost 不存在: ${input.boostId}")
            service.delete(input.boostId)
            mcpSuccess(mapOf("deleted" to entity.id, "name" to entity.name))
        }
    )
}

private sealed interface AuraBoostQuery {
    data class List(val managerId: String?) : AuraBoostQuery
    data class Get(val id: String) : AuraBoostQuery
}

private data class AuraBoostQueryInput(
    @field:JsonPropertyDescription("操作类型：LIST 列出全部摘要，GET 读取单条完整配置。")
    val action: String,
    @field:JsonPropertyDescription("AuraBoost id（8 位短 id），仅 action=GET 时必填。")
    val id: String? = null,
    @field:JsonPropertyDescription("可选：仅 action=LIST 时有效，按卡组 managerId 过滤。")
    val managerId: String? = null
) {
    fun toQuery(): AuraBoostQuery = when (action.uppercase()) {
        "LIST" -> AuraBoostQuery.List(managerId)
        "GET" -> AuraBoostQuery.Get(id ?: throw McpBadInput("action=GET 必须提供 id"))
        else -> throw McpBadInput("未知 action: $action，支持 LIST / GET")
    }
}

private data class DeleteAuraBoostInput(
    @field:JsonPropertyDescription("要删除的 AuraBoost id。")
    val boostId: String
)

/**
 * save_aura_boost 的 MCP 专用扁平 input（不直接复用 repository 的 SaveAuraBoostInput，
 * 因为后者 conditionId/targetConditionId 为必填，无法表达"内联创建"分支）。
 * conditionId 与 conditionTreeJson 互斥（同 targetConditionId / targetConditionTreeJson）。
 */
private data class SaveAuraBoostMcpInput(
    @field:JsonPropertyDescription("配置名称。")
    val name: String? = null,
    @field:JsonPropertyDescription("触发条件树 id（复用已有树）。与 conditionTreeJson 互斥：提供 conditionTreeJson 时此字段留空。")
    val conditionId: String? = null,
    @field:JsonPropertyDescription("触发条件树内联 JSON（一次性树，无需先建模板）：完整条件树 JSON 文本 {id,name,root}，root 为节点对象。与 conditionId 互斥：提供此字段时自动建树。")
    val conditionTreeJson: String? = null,
    @field:JsonPropertyDescription("受益过滤条件树 id（复用已有树）。与 targetConditionTreeJson 互斥。")
    val targetConditionId: String? = null,
    @field:JsonPropertyDescription("受益过滤条件树内联 JSON（一次性树，无需先建模板）：完整条件树 JSON 文本 {id,name,root}。与 targetConditionId 互斥：提供此字段时自动建树。")
    val targetConditionTreeJson: String? = null,
    @field:JsonPropertyDescription("命中后加给受益卡的分值。")
    val score: Double,
    @field:JsonPropertyDescription("归属卡组 managerId（可选，来自 card_group(action=LIST)）。")
    val managerId: String? = null,
    @field:JsonPropertyDescription("可选：更新已有 AuraBoost 时传其 id；不传则新建。")
    val existingId: String? = null
)

private fun AuraBoostEntity.toSummary(): Map<String, Any?> = mapOf(
    "id" to id,
    "name" to name,
    "conditionId" to conditionId,
    "targetConditionId" to targetConditionId,
    "score" to score,
    "managerId" to managerId
)
