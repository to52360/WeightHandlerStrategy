package lin.repository.delete_snapshot

/**
 * delete_snapshot 表行实体（字段与表列一一对应）。
 *
 * @param resource 被删资源类型：card_group / card_pool / condition_tree / aura_boost / combo_plan / evaluator_tree
 * @param entityId 被删资源原 id（card_pool 用 fileName，不含 .cardgroup 后缀）
 * @param entityName 展示名（list 回显用）
 * @param payload 完整快照 JSON（恢复所需全量，随 resource 而异，含原 id）
 * @param createdAt ISO 时间戳（排序 + 保留 N 条清理）
 */
data class DeleteSnapshotEntity(
    val snapshotId: String,
    val resource: String,
    val entityId: String,
    val entityName: String?,
    val payload: String,
    val createdAt: String
)
