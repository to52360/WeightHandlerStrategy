package lin.mcp

import com.fasterxml.jackson.annotation.JsonPropertyDescription
import lin.mcp.action.*
import lin.repository.delete_snapshot.DeleteSnapshotEntity
import lin.repository.delete_snapshot.RestoreResult
import lin.repository.delete_snapshot.SnapshotStore

/**
 * delete 快照恢复域 MCP 工具提供者（T-010）：
 * - actions：resource=delete_snapshot 的 get/list（只读快照目录，list 不回 payload 省 token）。
 * - provide()：restore_snapshot 写工具（一键按原 id 恢复被删资源）。
 * delete_snapshot 不支持 delete（快照由保留 N 条策略自动清理）。
 */
class RestoreSnapshotToolProvider(
    private val store: SnapshotStore,
    /** 恢复路由：与 get/list/delete 共用同一个 [ActionRegistry]（索引只建一次），按 resource 取 [RestoreCapability]。 */
    private val registry: ActionRegistry
) : McpToolProvider {

    override val actions: List<ResourceActions> = listOf(
        ResourceActions(
            resource = ActionResources.DELETE_SNAPSHOT,
            capabilities = listOf(
                GetCapability(
                    fieldHint = "snapshotId（由 delete 响应或 list(resource=delete_snapshot) 返回）"
                ) { id -> snapshotDetail(id) },
                ListCapability { snapshotSummaries() }
            )
        )
    )

    override fun provide(): List<McpToolHandler> = listOf(
        typedTool<RestoreSnapshotInput>(
            name = "restore_snapshot",
            description = """一键恢复此前被 delete 删除的资源（原 id 保留，引用不断）。传入 delete 响应里的 snapshotId（或 list(resource=delete_snapshot) 的 snapshotId）。
按原 id 写回：combo_plan / aura_boost / evaluator_tree / condition_tree / purpose_tag / strategy_preset 重建单条（预设含树白名单 + 时序两个维度项），card_group 级联重建 manager+bindings+关联树+卡组维度项+预设引用，card_pool 重建 .cardgroup 文件。
恢复前会做冲突检查：原 id 已被现有数据占用时拒绝（不覆盖、不改名），需先删除/改名现有数据再恢复。
另：恢复时会校验快照里的引用是否仍存在 —— card_group 快照引用的用途预设已不存在、或 evaluator_tree 快照的归属卡组已不存在时拒绝恢复（先恢复被引用者，避免造出悬空引用）。"""
        ) { input ->
            if (input.snapshotId.isBlank()) {
                return@typedTool mcpError("snapshotId 不能为空（来自 delete 响应，或 list(resource=delete_snapshot)）")
            }
            val result = restore(input.snapshotId)
            if (result.isError) {
                mcpError(result.message)
            } else {
                mcpSuccess(mapOf("restored" to true, "message" to result.message))
            }
        }
    )

    /**
     * 按快照行路由到对应资源的恢复能力（各 Provider 声明 [RestoreCapability]）。
     * 领域逻辑在各域服务里，这里只做"按 resource 找人"。
     */
    private fun restore(snapshotId: String): RestoreResult {
        val snapshot = store.find(snapshotId)
            ?: return RestoreResult(
                "恢复失败：快照 $snapshotId 不存在（可能已被保留策略清理）。当前可用: list(resource=delete_snapshot)",
                isError = true
            )
        val restorable = registry.restorable(snapshot.resource)
            ?: return RestoreResult(
                "恢复失败：资源类型 ${snapshot.resource} 不支持恢复（快照 ${snapshot.snapshotId}）",
                isError = true
            )
        return restorable.handle(snapshot.entityId, snapshot.payload)
    }

    // ── delete_snapshot 的 get / list 实现（业务逻辑具名，行为可点名）──

    private fun snapshotSummaries(): McpToolResult =
        mcpSuccess(store.list().map { it.toSummary() })

    private fun snapshotDetail(id: String): McpToolResult {
        val snapshot = store.find(id)
            ?: return mcpError("快照不存在: $id。当前可用: ${store.list().map { it.snapshotId }}")
        val payloadObj = runCatching { mcpMapper.readValue(snapshot.payload, Any::class.java) }
            .getOrElse { snapshot.payload }
        return mcpSuccess(
            mapOf(
                "snapshotId" to snapshot.snapshotId,
                "resource" to snapshot.resource,
                "entityId" to snapshot.entityId,
                "entityName" to snapshot.entityName,
                "createdAt" to snapshot.createdAt,
                "payload" to payloadObj
            )
        )
    }
}

private fun DeleteSnapshotEntity.toSummary(): Map<String, Any?> = mapOf(
    "snapshotId" to snapshotId,
    "resource" to resource,
    "entityId" to entityId,
    "entityName" to entityName,
    "createdAt" to createdAt
)

private data class RestoreSnapshotInput(
    @field:JsonPropertyDescription("要恢复的快照 id（来自 delete 响应返回的 snapshotId，或 list(resource=delete_snapshot) 的 snapshotId）")
    val snapshotId: String
)
