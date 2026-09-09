package lin.mcp

import com.fasterxml.jackson.annotation.JsonPropertyDescription
import lin.mcp.action.*
import lin.repository.aura_boost.AuraBoostConfigService
import lin.repository.aura_boost.AuraBoostEntity
import lin.repository.aura_boost.SaveAuraBoostInlineInput
import lin.repository.delete_snapshot.DeleteSnapshotService
import lin.repository.delete_snapshot.SnapshotPayloads
import lin.repository.delete_snapshot.SnapshotResource

/**
 * Push 广播评分配置（aura-boost）域 MCP 工具提供者（写工具 + 动作同文件）：
 * - [AuraBoostAction]：resource=aura_boost 的 get/list/delete（原 aura_boost / delete_aura_boost 工具）。
 * - provide()：save_aura_boost 写工具。
 *
 * AuraBoost = 触发条件树（conditionId，全局检测）命中后，给 targetConditionId（受益卡过滤）命中的卡加分（Q-024 后即**费值**）。
 * additive 独立通道：命中费值与评估树费值相加；光环加分只走 AuraBoost，评估树不写光环条件（D-004）。
 * managerId 为消费方归属（卡组级配置），引用的条件树是全局资源（D-003）。
 * T-011：内联建树（0~2 棵）+ boost 行多步写的编排与事务已下沉至
 * [lin.repository.aura_boost.AuraBoostConfigService.saveWithInlineTrees]，Provider 不再持有
 * TransactionTemplate / ConditionTreeConfigService。
 */
class AuraBoostToolProvider(
    private val service: AuraBoostConfigService,
    snapshotService: DeleteSnapshotService
) : McpToolProvider {

    override val actions: List<ResourceAction> = listOf(
        AuraBoostAction(service, snapshotService)
    )

    override fun provide(): List<McpToolHandler> = listOf(
        typedTool<SaveAuraBoostMcpInput>(
            name = "save_aura_boost",
            description = """
                创建或更新一条 Push 广播评分配置（AuraBoost）。
                语义：触发条件树命中（如"莱妮莎在场"）→ 给受益过滤条件树命中的卡 +score（**费值**，Q-024 后按费配）。
                additive 通道：加分（费值）与评估树加分相加，光环加分只走 AuraBoost，评估树不写光环条件（防双倍计分）。

                【条件树两种提供方式（二选一，互斥）】
                - 复用已有条件树：conditionId / targetConditionId 传已有树 id（来自 list(resource=condition_tree)）
                - 一次性内联创建：conditionTreeJson / targetConditionTreeJson 直接传条件树 JSON（{id,name,root}），
                  无需先 save_condition_tree 建模板，本工具自动建树并返回新 id

                managerId 关联卡组（消费方归属）；引用的条件树是全局资源。传 existingId 更新已有配置。

                enabled 启用开关（缺省保持原值，新建默认 true）：false = 配置仍留在库中可查，
                但**不进引擎**（临时停用通道）。⚠️ 这是**行级**开关，只能停用"这一条规则"，
                做不到"按卡组启停"——引擎侧按"所有已启用卡组"加载，无"当前卡组"概念
                （见单活卡组约束：启用一个卡组会自动禁用其余卡组）。
            """.trimIndent()
        ) { input ->
            val result = service.saveWithInlineTrees(
                SaveAuraBoostInlineInput(
                    name = input.name,
                    conditionId = input.conditionId,
                    conditionTreeJson = input.conditionTreeJson,
                    targetConditionId = input.targetConditionId,
                    targetConditionTreeJson = input.targetConditionTreeJson,
                    score = input.score,
                    managerId = input.managerId,
                    existingId = input.existingId,
                    enabled = input.enabled
                )
            )
            mcpSuccess(
                mapOf(
                    "id" to result.id, "name" to input.name, "score" to input.score,
                    "conditionId" to result.conditionId, "targetConditionId" to result.targetConditionId,
                    "enabled" to (input.enabled ?: true)
                )
            )
        }
    )

    // ── 动作：aura_boost get/list/delete ──

    private class AuraBoostAction(
        private val service: AuraBoostConfigService,
        private val snapshotService: DeleteSnapshotService
    ) : GetAction, ListAction, DeleteAction {

        override val resource: String = ActionResources.AURA_BOOST

        override val supportsManagerIdFilter: Boolean = true

        override fun handleList(managerId: String?): McpToolResult {
            val list = service.loadAll()
                .filter { managerId == null || it.managerId == managerId }
            return mcpSuccess(list.map { it.toSummary() })
        }

        override fun handleGet(id: String): McpToolResult {
            val entity = service.findById(id)
                ?: return mcpError("AuraBoost 不存在: $id")
            return mcpSuccess(entity.toSummary())
        }

        override val getFieldHint: String = "AuraBoost id（8 位短 id，由 list(resource=aura_boost) 返回）"

        override fun handleDelete(id: String): McpToolResult {
            val entity = service.findById(id)
                ?: return mcpError("AuraBoost 不存在: $id")
            val payload = SnapshotPayloads.auraBoost(entity)
            val snapshotId = snapshotService.deleteWithSnapshot(
                resource = SnapshotResource.AURA_BOOST,
                entityId = entity.id,
                entityName = entity.name,
                payload = payload
            ) {
                service.delete(id)
            }
            return mcpSuccess(mapOf("deleted" to entity.id, "name" to entity.name, "snapshotId" to snapshotId))
        }

        override val deleteFieldHint: String = "AuraBoost id（由 list(resource=aura_boost) 返回）"

        override val deleteSemantics: String =
            "删除前落快照（delete_snapshot）并回 snapshotId，可经 restore_snapshot 一键恢复（原 id 保留）"
    }
}

private fun AuraBoostEntity.toSummary(): Map<String, Any?> = mapOf(
    "id" to id,
    "name" to name,
    "conditionId" to conditionId,
    "targetConditionId" to targetConditionId,
    "score" to score,
    "managerId" to managerId,
    "enabled" to enabled
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
    @field:JsonPropertyDescription("命中后加给受益卡的费值（Q-024 后即费：1 分 = 0.4 费，如 3.2 = 原 8 分；按「≈ 典型卡等效费」标定，D-PV-012）。")
    val score: Double,
    @field:JsonPropertyDescription("归属卡组 managerId（可选，来自 list(resource=card_group)）。")
    val managerId: String? = null,
    @field:JsonPropertyDescription("可选：更新已有 AuraBoost 时传其 id；不传则新建。")
    val existingId: String? = null,
    @field:JsonPropertyDescription("可选：启用开关。false = 留在库中可查但不进引擎（临时停用）；不传=保持原值，新建默认 true。注意是行级开关，不能按卡组启停。")
    val enabled: Boolean? = null
)
