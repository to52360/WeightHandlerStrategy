package lin.repository.combo_plan

import lin.bean.usePlan.ComboRelation
import lin.repository.card_group.CardGroupService
import lin.repository.delete_snapshot.*
import lin.rule.tree.CardGroupBinding
import lin.rule.tree.CardGroupManagerConfig
import lin.utils.nextShortId

/**
 * combo_plan 的删除 / 恢复（T-TG-021：业务归域，导出 [SnapshotOps] 由快照域编排）
 * 与保存（D-DC-007：本资源**写入校验的单点**）。
 */
class ComboPlanService(
    private val repository: ComboPlanDefinitionRepository,
    private val cardGroupService: CardGroupService
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

    /**
     * 保存（新建 / 覆盖）Combo 编排 —— **本资源写入校验的单点**（D-DC-007）。
     *
     * 校验项：目标卡组存在 ＋ 核心组与依赖组各自非空（combo 本义是组合，空集合无意义）＋ 分组 id 归属该卡组 ＋ relation 合法。
     * 调用方（UI `ComboPlanStore` / MCP `save_combo_plan`）只把 [ComboPlanSaveResult.Rejected] 转成各自形态的提示，
     * **不得另行实现一遍校验**（否则同一事实两套实现）。
     *
     * @param plan 待保存实体；`id` 留空（空白串）= 新建，短 id 由本方法分配并经 [ComboPlanSaveResult.Saved.id] 回传
     * （原先两侧各自 `nextShortId()` 的重复逻辑收在此处）。
     */
    fun save(plan: ComboPlanDefinitionEntity): ComboPlanSaveResult {
        val managers = cardGroupService.loadAll(onlyEnabled = false)
        val manager = managers.find { it.cardGroupManagerId == plan.managerId }
            ?: return ComboPlanSaveResult.Rejected(
                listOf(ComboPlanProblem.ManagerNotFound(plan.managerId, managers))
            )

        val coreIds = plan.coreGroupIdSet()
        val depIds = plan.depGroupIdSet()
        val validIds = manager.bindings.map { it.id }.toSet()
        val invalidCore = coreIds.filterNot { it in validIds }
        val invalidDep = depIds.filterNot { it in validIds }

        // 聚合全部问题（与 UI 侧 FormPrompt 一次列全一致），不"遇到第一条就返回"
        val problems = buildList {
            if (coreIds.isEmpty()) add(ComboPlanProblem.EmptyCore)
            if (depIds.isEmpty()) add(ComboPlanProblem.EmptyDep)
            if (invalidCore.isNotEmpty() || invalidDep.isNotEmpty()) {
                add(ComboPlanProblem.ForeignGroups(invalidCore, invalidDep, manager))
            }
            if (ComboRelation.entries.none { it.name == plan.relation }) {
                add(ComboPlanProblem.InvalidRelation(plan.relation))
            }
        }
        if (problems.isNotEmpty()) return ComboPlanSaveResult.Rejected(problems)

        val finalId = plan.id.trim().takeIf { it.isNotEmpty() } ?: nextShortId()
        repository.save(plan.copy(id = finalId))
        return ComboPlanSaveResult.Saved(finalId, manager.name)
    }
}

/** 保存结果：[Saved] 落库成功并回传最终 id，[Rejected] 校验不通过（未落库）。 */
sealed interface ComboPlanSaveResult {
    data class Saved(val id: String, val managerName: String) : ComboPlanSaveResult
    data class Rejected(val problems: List<ComboPlanProblem>) : ComboPlanSaveResult
}

/**
 * 保存校验问题（单点产出的**结构化**结果）：[fieldId] / [label] / [message] 供 UI 转成 `FieldProblem` 呈现，
 * 附加字段供 MCP 复现带候选列表的提示文案 —— 两侧共享同一份判定，只各渲染一次。
 */
sealed class ComboPlanProblem(val fieldId: String, val label: String, val message: String) {

    class ManagerNotFound(managerId: String, val available: List<CardGroupManagerConfig>) :
        ComboPlanProblem("manager", "目标卡组", "卡组/管理器不存在: $managerId")

    object EmptyCore : ComboPlanProblem(
        "core", "核心组", "coreGroupIds 核心分组 ID 列表不能为空，必须包含至少一个有效分组 ID"
    )

    object EmptyDep : ComboPlanProblem(
        "dep", "依赖组", "depGroupIds 依赖分组 ID 列表不能为空！Combo 方案必须包含核心组与依赖组才能构成协同加分或时序关联。"
    )

    class ForeignGroups(
        val invalidCore: List<String>,
        val invalidDep: List<String>,
        val managerName: String,
        val managerId: String,
        val validBindings: List<CardGroupBinding>
    ) : ComboPlanProblem(
        "binding", "卡组分组选择",
        "提供的分组 ID 不属于卡组 [$managerName] ($managerId)。" +
                "无效核心组: $invalidCore, 无效依赖组: $invalidDep"
    ) {
        constructor(
            invalidCore: List<String>,
            invalidDep: List<String>,
            manager: CardGroupManagerConfig
        ) : this(invalidCore, invalidDep, manager.name, manager.cardGroupManagerId, manager.bindings)
    }

    class InvalidRelation(val relation: String) : ComboPlanProblem(
        "relation", "执行顺序关系",
        "未知 relation: $relation。合法选项: ${ComboRelation.entries.map { it.name }}"
    )
}
