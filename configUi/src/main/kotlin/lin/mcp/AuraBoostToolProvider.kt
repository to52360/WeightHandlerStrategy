package lin.mcp

import com.fasterxml.jackson.annotation.JsonPropertyDescription
import lin.repository.aura_boost.AuraBoostConfigService
import lin.repository.aura_boost.AuraBoostEntity
import lin.repository.aura_boost.SaveAuraBoostInput
import lin.repository.condition_tree.ConditionTreeConfigService

/**
 * Push 广播评分配置（aura-boost）MCP 工具提供者。
 *
 * AuraBoost = 触发条件树（conditionId，全局检测）命中后，给 targetConditionId（受益卡过滤）命中的卡加分。
 * additive 独立通道：命中分与评估树分相加；光环加分只走 AuraBoost，评估树不写光环条件（D-004）。
 * managerId 为消费方归属（卡组级配置），引用的条件树是全局资源（D-003）。
 */
class AuraBoostToolProvider(
    private val service: AuraBoostConfigService,
    private val conditionTreeService: ConditionTreeConfigService
) : McpToolProvider {
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

        typedTool<SaveAuraBoostInput>(
            name = "save_aura_boost",
            description = """
                创建或更新一条 Push 广播评分配置（AuraBoost）。
                语义：触发条件树 conditionId 命中（如"莱妮莎在场"）→ 给 targetConditionId 命中（如"是法术且cost≤2"）的卡 +score。
                additive 通道：加分与评估树分相加，光环加分只走 AuraBoost，评估树不写光环条件（防双倍计分）。
                前置：conditionId / targetConditionId 需先用 condition_tree(action=SAVE) 创建。
                managerId 关联卡组（消费方归属）；引用的条件树是全局资源。传 existingId 更新已有配置。
            """.trimIndent()
        ) { input ->
            if (conditionTreeService.findById(input.conditionId) == null) {
                return@typedTool mcpError("触发条件树不存在: ${input.conditionId}（先 condition_tree(action=SAVE) 创建）")
            }
            if (conditionTreeService.findById(input.targetConditionId) == null) {
                return@typedTool mcpError("受益过滤条件树不存在: ${input.targetConditionId}（先 condition_tree(action=SAVE) 创建）")
            }
            val id = service.save(
                SaveAuraBoostInput(
                    name = input.name,
                    conditionId = input.conditionId,
                    targetConditionId = input.targetConditionId,
                    score = input.score,
                    managerId = input.managerId,
                    existingId = input.existingId
                )
            )
            mcpSuccess(mapOf("id" to id, "name" to input.name, "score" to input.score))
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

private fun AuraBoostEntity.toSummary(): Map<String, Any?> = mapOf(
    "id" to id,
    "name" to name,
    "conditionId" to conditionId,
    "targetConditionId" to targetConditionId,
    "score" to score,
    "managerId" to managerId
)
