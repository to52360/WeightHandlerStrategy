package lin.repository.combo_plan

import lin.repository.delete_snapshot.*

/** combo_plan 的删除 / 恢复（T-TG-021：业务归域，导出 [SnapshotOps] 由快照域编排）。 */
class ComboPlanService(
    private val repository: ComboPlanDefinitionRepository
) {
    /** 导出本资源的删除操作值（业务校验在此；落快照与删除由 [lin.repository.delete_snapshot.SnapshotStore] 编排）。 */
    fun deleteOps(): SnapshotOps = SnapshotOps(
        collect = { entityId ->
            val entity = repository.findById(entityId)
                ?: throw SnapshotRefused(
                    "Combo 方案不存在: $entityId。当前存在的 Combo 列表: ${
                        repository.findAll().map { mapOf("id" to it.id, "managerId" to it.managerId) }
                    }"
                )
            SnapshotDraft(
                entityName = entity.managerId,
                payload = SnapshotPayloads.comboPlan(entity),
                echo = mapOf("deleted" to true, "id" to entityId)
            )
        },
        remove = { entityId -> repository.deleteById(entityId) }
    )

    /** 按快照写回（原 id 保留）；原 id 已被占用则拒绝。 */
    fun restoreFromSnapshot(id: String, payload: String): RestoreResult {
        repository.findById(id)?.let {
            return RestoreResult(
                "恢复失败：原 id=$id 已被现有数据占用。请先删除/改名现有数据再恢复",
                isError = true
            )
        }
        val entity = SnapshotPayloads.mapper.readValue(payload, ComboPlanDefinitionEntity::class.java)
        repository.save(entity)
        return RestoreResult("已恢复 combo_plan ${entity.id}（原 id 保留）", isError = false)
    }

    /**
     * K-TG-014：声明本域在「卡组」聚合根下的**从属资源**（采集 / 级联删 / 恢复三面同源），
     * 逐条复用 [SnapshotPayloads.comboPlan] 与 [restoreFromSnapshot] ⇒ 零第二套 JSON 形态。
     */
    fun cardGroupChild(): CardGroupChild = CardGroupChild(
        key = "comboPlans",
        collect = { managerId ->
            SnapshotPayloads.itemsArray(repository.findByManager(managerId).map { SnapshotPayloads.comboPlan(it) })
        },
        delete = { managerId -> repository.deleteByManager(managerId) },
        restore = { _, payload ->
            val failures = payload.mapNotNull { item ->
                restoreFromSnapshot(item["id"].asText(), item.toString()).takeIf { it.isError }
            }
            RestoreResult(
                "恢复 combo_plan ${payload.size()} 条" +
                        if (failures.isEmpty()) "" else "，${failures.size} 条失败：${failures.first().message}",
                isError = failures.isNotEmpty()
            )
        }
    )
}
