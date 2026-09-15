package lin.repository.card_group

import lin.repository.delete_snapshot.CardGroupChild
import lin.repository.delete_snapshot.CardGroupSnapshot
import lin.repository.delete_snapshot.EvaluatorTreeSnapshot
import lin.repository.delete_snapshot.OrphanRowGuard
import lin.repository.delete_snapshot.RestoreResult
import lin.repository.delete_snapshot.SnapshotDraft
import lin.repository.delete_snapshot.SnapshotOps
import lin.repository.delete_snapshot.SnapshotPayloads
import lin.repository.delete_snapshot.SnapshotRefused
import lin.rule.tree.EvaluatorTreeBindingType
import lin.rule.tree.EvaluatorTreeConfig
import lin.ui.service.TreeConfigService
import org.springframework.transaction.support.TransactionTemplate

/**
 * 卡组方案的级联删除 / 恢复（T-TG-010：业务归域；K-TG-014：从属资源补齐）。
 *
 * 为什么单独成一个类：卡组是**聚合根** —— 关联评估树（`tree_config.manager_id`）、绑定条目、
 * 卡组维度项（`strategy_dimension_item.scope='CARD_GROUP'`）都从属于它，且都以 managerId 为归属键。
 * 也就是说它跨的域与卡组**强相关**，不是"无关域拼装"（refactoring-review Gate 2 #3 的反面）。
 *
 * **从属资源清单**（`children`，K-TG-014）：本类**不认识各域** —— 采集 / 删除 / 恢复都按
 * [CardGroupChild] 值遍历（新增从属资源 = 装配点 +1 行）。`children` **未覆盖**的另两类
 * （关联评估树、卡组维度项）因其快照载体是**类型化字段**且老快照 JSON 依赖它们，**保持原样不动**
 * （全量值化会破坏老快照可恢复性，收益只是形式统一 ⇒ 不做，见专项 §4 注）。
 */
class CardGroupCascadeDeleteService(
    private val groupService: CardGroupService,
    private val treeConfigService: TreeConfigService,
    private val presetRepository: StrategyPresetRepository,
    private val tx: TransactionTemplate,
    /** 从属资源清单（唯一装配点在 `ModelsDefine`；各域自己声明 `cardGroupChild()`）。 */
    private val children: List<CardGroupChild>,
    /**
     * 残留自证守卫（K-TG-014 §5.4）。
     * ⚠️ 属**机制**依赖（同 [tx] 层级），不是业务域服务 ⇒ 本类业务依赖仍是 3 个
     * （`groupService` / `treeConfigService` / `presetRepository`）。
     */
    private val orphanRowGuard: OrphanRowGuard
) {
    /**
     * 导出 card_group 的删除操作值：采集卡组全貌（manager + bindings + 可解析的关联树 + 卡组维度项），
     * 不存在时抛 [SnapshotRefused]；删除 = 关联树 + manager + 卡组维度项（与落快照同事务）。
     */
    fun deleteOps(): SnapshotOps = SnapshotOps(
        collect = { entityId -> collectManagerSnapshot(entityId) },
        remove = { entityId -> cascadeRemove(entityId) }
    )

    private fun collectManagerSnapshot(entityId: String): SnapshotDraft {
        val manager = groupService.loadAllManagers().firstOrNull { it.id == entityId }
            ?: throw SnapshotRefused("方案不存在: $entityId")
        val bindings = groupService.loadBindings(entityId)
        val linkedTreeIds = treeConfigService.loadSummaries().filter { it["managerId"] == entityId }
        val treeNames = linkedTreeIds.map { it["name"] as? String ?: "" }

        // 采集侧只收可解析的树（payload 需要 root/leafConfigs）
        val fullTrees = linkedTreeIds.mapNotNull { s ->
            val treeId = s["id"] as String
            val found = treeConfigService.findById(treeId)
            if (found?.second == null) null else treeId to found
        }
        // K-TG-014：值化从属资源（各域自采）—— 与删除侧同源，保证"删得掉、也恢复得回"
        val childPayloads = children.associate { it.key to it.collect(entityId) }
        val snapshot = CardGroupSnapshot(
            managerName = manager.name,
            sourceFile = manager.sourceFile,
            enabled = manager.enabled,
            managerDescription = manager.description,
            managerStatus = manager.status,
            defaultIncludeDerived = manager.defaultIncludeDerived,
            bindings = bindings,
            trees = fullTrees.map { (treeId, found) ->
                val entity = found.first!!
                val config = found.second!!
                EvaluatorTreeSnapshot(
                    id = treeId,
                    name = entity.name,
                    description = entity.description,
                    enabled = entity.enabled,
                    managerId = entity.managerId,
                    bindingType = config.bindingType.name,
                    bindingIds = config.bindingIds,
                    root = config.root,
                    leafConfigs = config.leafConfigs
                )
            },
            presetId = manager.presetId,
            dimensionItems = presetRepository.findItems(DimensionScope.CARD_GROUP, entityId),
            children = childPayloads
        )

        return SnapshotDraft(
            entityName = manager.name,
            payload = SnapshotPayloads.cardGroup(snapshot),
            echo = mapOf(
                "deleted" to true,
                "managerId" to entityId,
                "managerName" to manager.name,
                "deletedBindings" to bindings.map { it.name },
                "deletedTrees" to treeNames,
                // 各从属资源的条数（空的不列，避免回显噪声）
                "deletedChildren" to childPayloads.filterValues { it.size() > 0 }.mapValues { it.value.size() },
                "totalDeleted" to (1 + bindings.size + fullTrees.size + childPayloads.values.sumOf { it.size() })
            )
        )
    }

    /**
     * **UI 路径**：级联删但**不落快照**（T-TG-035；`D-TG-016`：UI 删除是用户自主操作，不做恢复兜底）。
     *
     * 与 MCP 路径**共用同一份 [cascadeRemove]** ⇒ 两条路径的清理覆盖面不再分叉
     * （此前 UI 路径连评估树 / 卡组维度项都不删）。
     */
    fun cascadeDelete(managerId: String) {
        tx.execute { cascadeRemove(managerId) }
    }

    /**
     * 级联删：关联树 + manager + 卡组维度项 + 各域声明的从属资源
     * （与落快照同事务，由 [SnapshotStore] 编排）。
     *
     * 顺序无关：聚合整体消失，聚合内部的相互引用不构成约束
     * （如私有条件树被 aura / 评估树引用 —— 三者同批被删，故不校验引用）。
     */
    private fun cascadeRemove(entityId: String) {
        // 关联树全删（不设"可解析"前提，防解析失败的树残留）
        treeConfigService.loadSummaries()
            .filter { it["managerId"] == entityId }
            .forEach { s -> treeConfigService.delete(s["id"] as String) }
        groupService.deleteManager(entityId)
        // 卡组维度项一并清理：防孤儿行；防恢复复用原 id 时旧增量项意外复活
        presetRepository.deleteItems(DimensionScope.CARD_GROUP, entityId)
        // 从属资源（aura / combo / 卡组私有条件树 …）：清单由装配点给出，本类不认识各域
        children.forEach { it.delete(entityId) }
        // 自证：库中带归属键的表是否还有本卡组的行（漏登记从属资源即告警，只警告不删）
        orphanRowGuard.assertNoResidue(entityId)
    }

    /**
     * 按快照重建 manager + bindings + 关联树 + 卡组维度项 + 预设引用（原 id 全保留）；
     * 两类冲突拒绝：**原 id 被占用** / **快照引用的预设已不存在**（T-TG-020，防造出悬空引用）。
     */
    fun restoreFromSnapshot(id: String, payload: String): RestoreResult {
        groupService.loadAllManagers().firstOrNull { it.id == id }?.let { occupied ->
            return RestoreResult(
                "恢复失败：原 id=$id 已被现有数据占用（现有名称: ${occupied.name}）。请先删除/改名现有数据再恢复",
                isError = true
            )
        }
        val p = SnapshotPayloads.cardGroupMapper.readValue(payload, CardGroupSnapshot::class.java)
        // T-TG-020：卡组删除后预设会失去最后一个引用而被删 ⇒ 再恢复卡组时快照里的 presetId 可能已悬空。
        // 写回前拒绝：静默置空会把"用预设"悄悄变成"不用预设"（兜底全开，方向与用户意图相反），
        // 而恢复的本义是"与快照一致"。
        val presetId = p.presetId?.takeIf { it.isNotBlank() }
        if (presetId != null && presetRepository.findPresetById(presetId) == null) {
            return RestoreResult(
                "恢复失败：快照引用的用途预设已不存在（presetId=$presetId），直接恢复会造出悬空引用" +
                        "（该卡组全部用途树将静默失效）。请先 restore_snapshot 恢复该预设快照后重试；" +
                        "若确不再需要该引用，可先恢复预设再用 save_card_group_preset（不带 presetId）清空引用。",
                isError = true
            )
        }
        return tx.execute {
            groupService.saveManager(
                ManagerSaveCommand(
                    name = p.managerName,
                    sourceFile = p.sourceFile,
                    enabled = p.enabled,
                    bindings = p.bindings.map { it.copy(managerId = "") },
                    existingId = id,
                    managerDescription = p.managerDescription,
                    managerStatus = p.managerStatus,
                    defaultIncludeDerived = p.defaultIncludeDerived
                )
            )
            // saveManager 有意不写 preset_id（整体替换语义），故恢复时单独写回（用上面校验过的 presetId）
            groupService.setPresetReference(id, presetId)
            // 卡组维度项按维度覆盖写回（replaceItems 先删同维度再插，满足联合主键覆盖语义）
            p.dimensionItems.groupBy { it.dimension }.forEach { (dimension, items) ->
                presetRepository.replaceItems(DimensionScope.CARD_GROUP, id, dimension, items)
            }
            p.trees.forEach { t ->
                treeConfigService.saveConfig(
                    name = t.name,
                    config = EvaluatorTreeConfig(
                        bindingType = EvaluatorTreeBindingType.valueOf(t.bindingType),
                        bindingIds = t.bindingIds,
                        root = t.root,
                        leafConfigs = t.leafConfigs
                    ),
                    existingId = t.id,
                    enabled = t.enabled,
                    managerId = t.managerId,
                    description = t.description
                )
            }
            // K-TG-014：从属资源按各域声明的 restore 写回（老快照无 children ⇒ 空数组 = 无事可做）
            val childResults = children.map { child ->
                child to child.restore(id, p.children[child.key] ?: SnapshotPayloads.mapper.createArrayNode())
            }
            val failed = childResults.filter { it.second.isError }
            RestoreResult(
                "已恢复 card_group $id（manager + ${p.bindings.size} binding + ${p.trees.size} 棵关联树" +
                        " + preset=${presetId ?: "null"} + ${p.dimensionItems.size} 条维度项" +
                        " + 从属资源 ${childResults.count { !it.second.isError }} 类：${
                            childResults.joinToString("、") { "${it.first.key} ${it.second.message}" }
                        }，原 id 全保留）" +
                        if (failed.isEmpty()) "" else "；❌ ${failed.size} 类失败：${
                            failed.joinToString("；") { it.second.message }
                        }",
                isError = failed.isNotEmpty()
            )
        }!!
    }
}
