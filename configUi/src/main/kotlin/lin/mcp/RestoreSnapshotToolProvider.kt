package lin.mcp

import com.fasterxml.jackson.annotation.JsonPropertyDescription
import lin.mcp.action.ActionResources
import lin.mcp.action.GetAction
import lin.mcp.action.ListAction
import lin.mcp.action.ResourceAction
import lin.repository.delete_snapshot.DeleteSnapshotEntity
import lin.repository.delete_snapshot.DeleteSnapshotService

/**
 * delete 快照恢复域 MCP 工具提供者（T-010）：
 * - [DeleteSnapshotAction]：resource=delete_snapshot 的 get/list（只读快照目录，list 不回 payload 省 token）。
 * - provide()：restore_snapshot 写工具（一键按原 id 恢复被删资源）。
 * delete_snapshot 不支持 delete（快照由保留 N 条策略自动清理）。
 */
class RestoreSnapshotToolProvider(
    private val service: DeleteSnapshotService
) : McpToolProvider {

    override val actions: List<ResourceAction> = listOf(
        DeleteSnapshotAction(service)
    )

    override fun provide(): List<McpToolHandler> = listOf(
        typedTool<RestoreSnapshotInput>(
            name = "restore_snapshot",
            description = """一键恢复此前被 delete 删除的资源（原 id 保留，引用不断）。传入 delete 响应里的 snapshotId（或 list(resource=delete_snapshot) 的 snapshotId）。
按原 id 写回：combo_plan / aura_boost / evaluator_tree / condition_tree 重建单条，card_group 级联重建 manager+bindings+关联树，card_pool 重建 .cardgroup 文件。
恢复前会做冲突检查：原 id 已被现有数据占用时拒绝（不覆盖、不改名），需先删除/改名现有数据再恢复。"""
        ) { input ->
            if (input.snapshotId.isBlank()) {
                return@typedTool mcpError("snapshotId 不能为空（来自 delete 响应，或 list(resource=delete_snapshot)）")
            }
            val result = service.restore(input.snapshotId)
            if (result.isError) {
                mcpError(result.message)
            } else {
                mcpSuccess(mapOf("restored" to true, "message" to result.message))
            }
        }
    )

    private class DeleteSnapshotAction(
        private val service: DeleteSnapshotService
    ) : GetAction, ListAction {

        override val resource: String = ActionResources.DELETE_SNAPSHOT

        override fun handleList(managerId: String?): McpToolResult {
            return mcpSuccess(service.list().map { it.toSummary() })
        }

        override fun handleGet(id: String): McpToolResult {
            val snapshot = service.get(id)
                ?: return mcpError("快照不存在: $id。当前可用: ${service.list().map { it.snapshotId }}")
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

        override val getFieldHint: String = "snapshotId（由 delete 响应或 list(resource=delete_snapshot) 返回）"
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
