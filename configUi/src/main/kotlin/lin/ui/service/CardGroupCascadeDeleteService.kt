package lin.ui.service

import lin.repository.card_group.CardGroupService
import lin.repository.delete_snapshot.DeleteSnapshotService
import lin.repository.delete_snapshot.SnapshotPayloads
import lin.repository.delete_snapshot.SnapshotResource

/**
 * delete(card_group) 的级联删除结果（删除前快照，供 MCP 响应回显）。
 */
data class CascadeDeleteResult(
    val managerId: String,
    val managerName: String,
    val bindingNames: List<String>,
    val treeNames: List<String>,
    val totalDeleted: Int,
    /** T-010：删除前快照 id（delete_snapshot 表），供 restore_snapshot 一键恢复。 */
    val snapshotId: String?
)

/**
 * 卡组方案级联删除应用服务（T-011：事务下沉自 MCP Provider 的 CardGroupAction）。
 *
 * 语义：先删该方案全部关联评估树（含叶子），再删 manager——多步写包事务，
 * 中途失败不留半删。读与校验（manager / bindings / 关联树）留在事务外，
 * 事务内只做删除（内层 TreeConfigService.delete / CardGroupService.deleteManager
 * 自带的 tx.execute 以 REQUIRED 传播加入本事务，不嵌套新事务）。
 *
 * T-010：删除前采集完整快照（manager + bindings + 关联树，保留原 id）落 delete_snapshot 表，
 * 快照 insert 与级联删除包同一事务（经 [DeleteSnapshotService.deleteWithSnapshot]）。
 */
class CardGroupCascadeDeleteService(
    private val groupService: CardGroupService,
    private val treeConfigService: TreeConfigService,
    private val snapshotService: DeleteSnapshotService
) {
    /**
     * @return 删除成功返回删除前快照；manager 不存在返回 null。
     */
    fun deleteManager(id: String): CascadeDeleteResult? {
        val manager = groupService.loadAllManagers().firstOrNull { it.id == id }
            ?: return null
        val bindings = groupService.loadBindings(id)
        val bindingNames = bindings.map { it.name }
        val linkedTreeIds = treeConfigService.loadSummaries().filter { it["managerId"] == id }
        val treeNames = linkedTreeIds.map { it["name"] as? String ?: "" }

        // 事务外读好完整快照数据（Java SAM lambda 不能非局部 return）：
        // 关联树全量（保留原 tree id / root / leafConfigs），仅收集可解析的树。
        val fullTrees = linkedTreeIds.mapNotNull { s ->
            val treeId = s["id"] as String
            val found = treeConfigService.findById(treeId)
            if (found?.second == null) null else treeId to found
        }

        val payload = SnapshotPayloads.cardGroup(
            managerName = manager.name,
            sourceFile = manager.sourceFile,
            enabled = manager.enabled,
            managerDescription = manager.description,
            managerStatus = manager.status,
            defaultIncludeDerived = manager.defaultIncludeDerived,
            bindings = bindings,
            trees = fullTrees.map { (treeId, found) ->
                Triple(treeId, found.first!!, found.second!!)
            }
        )

        val snapshotId = snapshotService.deleteWithSnapshot(
            resource = SnapshotResource.CARD_GROUP,
            entityId = id,
            entityName = manager.name,
            payload = payload
        ) {
            fullTrees.forEach { (treeId, found) -> treeConfigService.delete(treeId) }
            groupService.deleteManager(id)
        }

        return CascadeDeleteResult(
            managerId = id,
            managerName = manager.name,
            bindingNames = bindingNames,
            treeNames = treeNames,
            totalDeleted = 1 + bindings.size + fullTrees.size,
            snapshotId = snapshotId
        )
    }
}
