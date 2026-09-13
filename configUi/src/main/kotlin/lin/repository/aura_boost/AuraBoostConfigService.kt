package lin.repository.aura_boost

import com.fasterxml.jackson.databind.ObjectMapper
import lin.repository.condition_tree.ConditionTreeConfigService
import lin.repository.condition_tree.resolveConditionTreeReference
import lin.repository.delete_snapshot.*
import org.springframework.transaction.support.TransactionTemplate
import java.util.*

/**
 * Push 广播评分配置（aura-boost D-001/D-002）持久层模型。
 *
 * @param managerId 消费方归属（D-003 分层）：aura_boost 是卡组级配置（圣契卡组才有莱妮莎光环），
 *                  归属在消费方，引用的条件树（condition_id/target_condition_id）是全局资源。
 * @param enabled   启用开关（T-SR-012，open-questions Q-OQ-002）：false = 留库但不进引擎。
 *                  ⚠️ 行级开关，**不能**做"按卡组启停"（引擎无"当前卡组"概念，见 Q-OQ-005）。
 */
data class AuraBoostEntity(
    val id: String,
    val name: String?,
    val conditionId: String,
    val targetConditionId: String,
    val score: Double,
    val managerId: String?,
    val enabled: Boolean = true
)

data class SaveAuraBoostInput(
    val name: String? = null,
    val conditionId: String,
    val targetConditionId: String,
    val score: Double,
    val managerId: String? = null,
    val existingId: String? = null,
    /** null = 保持原值（新建则 true），与 description/status 的"缺省不覆盖"语义一致。 */
    val enabled: Boolean? = null
)

/**
 * save_aura_boost 内联建树入参（T-011 下沉至服务层）。
 * conditionId / conditionTreeJson 互斥（同 targetConditionId / targetConditionTreeJson）：
 * 提供 treeJson 时由服务在事务内自动建一次性树，无需先 save_condition_tree。
 */
data class SaveAuraBoostInlineInput(
    val name: String? = null,
    val conditionId: String? = null,
    val conditionTreeJson: String? = null,
    val targetConditionId: String? = null,
    val targetConditionTreeJson: String? = null,
    val score: Double,
    val managerId: String? = null,
    val existingId: String? = null,
    /** null = 保持原值（新建则 true）。 */
    val enabled: Boolean? = null
)

/** save_aura_boost 落库结果（含实际生效的条件树 id，供调用方回显）。 */
data class AuraBoostInlineSaveResult(
    val id: String,
    val conditionId: String,
    val targetConditionId: String
)

class AuraBoostConfigService(
    private val repository: AuraBoostRepository,
    /** T-011：saveWithInlineTrees 需内联建树（resolveConditionTreeReference）。 */
    private val conditionTreeService: ConditionTreeConfigService,
    private val conditionTreeMapper: ObjectMapper,
    /** T-011：内联建树（0~2 棵）+ boost 行多步写的事务边界（由 Provider 层下沉至此）。 */
    private val tx: TransactionTemplate
) {

    // ─────────────────── 删除 + 快照 / 恢复（T-TG-021：业务归域 + 机制由快照域编排）───────────────────

    /** 导出本资源的删除操作值；不存在时抛 [SnapshotRefused]（不落快照、不删除）。 */
    fun deleteOps(): SnapshotOps = SnapshotOps(
        collect = { entityId ->
            val entity = findById(entityId) ?: throw SnapshotRefused("AuraBoost 不存在: $entityId")
            SnapshotDraft(
                entityName = entity.name,
                payload = SnapshotPayloads.auraBoost(entity),
                echo = mapOf("deleted" to entity.id, "name" to entity.name)
            )
        },
        remove = { entityId -> delete(entityId) }
    )

    /** 按快照写回（含 enabled 启用状态）；原 id 已被占用则拒绝。 */
    fun restoreFromSnapshot(id: String, payload: String): RestoreResult {
        findById(id)?.let { occupied ->
            return RestoreResult(
                "恢复失败：原 id=$id 已被现有数据占用（现有名称: ${occupied.name}）。请先删除/改名现有数据再恢复",
                isError = true
            )
        }
        val entity = SnapshotPayloads.mapper.readValue(payload, AuraBoostEntity::class.java)
        save(
            SaveAuraBoostInput(
                name = entity.name,
                conditionId = entity.conditionId,
                targetConditionId = entity.targetConditionId,
                score = entity.score,
                managerId = entity.managerId,
                existingId = entity.id,
                // T-SR-012：恢复时一并还原启用状态（快照 payload 含 enabled）
                enabled = entity.enabled
            )
        )
        return RestoreResult("已恢复 aura_boost ${entity.id}（原 id 保留）", isError = false)
    }
    fun save(input: SaveAuraBoostInput): String {
        val id = input.existingId ?: UUID.randomUUID().toString().substring(0, 8)
        // T-SR-012：enabled 缺省（null）保持原值——UI 保存路径不传该字段时不会把已停用的规则重新启用
        val existingEnabled = repository.findById(id)?.enabled
        repository.save(
            AuraBoostEntity(
                id = id,
                name = input.name,
                conditionId = input.conditionId,
                targetConditionId = input.targetConditionId,
                score = input.score,
                managerId = input.managerId,
                enabled = input.enabled ?: existingEnabled ?: true
            )
        )
        return id
    }

    /**
     * T-011：save_aura_boost 整体编排（下沉自 AuraBoostToolProvider）——内联建触发/受益过滤
     * 条件树（0~2 棵，复用引用或 treeJson 自动创建）+ boost 行落库，包事务防「树建了、boost 没存」。
     * 校验失败抛 [lin.repository.condition_tree.ConditionTreeReferenceException]（事务回滚）。
     */
    fun saveWithInlineTrees(input: SaveAuraBoostInlineInput): AuraBoostInlineSaveResult = tx.execute {
        val triggerId = resolveConditionTreeReference(
            service = conditionTreeService,
            mapper = conditionTreeMapper,
            conditionId = input.conditionId,
            treeJson = input.conditionTreeJson,
            defaultName = "${input.name ?: "boost"}_trigger",
            label = "触发条件树",
            managerId = input.managerId
        )
        val targetId = resolveConditionTreeReference(
            service = conditionTreeService,
            mapper = conditionTreeMapper,
            conditionId = input.targetConditionId,
            treeJson = input.targetConditionTreeJson,
            defaultName = "${input.name ?: "boost"}_target",
            label = "受益过滤条件树",
            managerId = input.managerId
        )
        val id = save(
            SaveAuraBoostInput(
                name = input.name,
                conditionId = triggerId,
                targetConditionId = targetId,
                score = input.score,
                managerId = input.managerId,
                existingId = input.existingId,
                enabled = input.enabled
            )
        )
        AuraBoostInlineSaveResult(id = id, conditionId = triggerId, targetConditionId = targetId)
    }!!

    fun loadAll(): List<AuraBoostEntity> = repository.findAll()

    fun findById(id: String): AuraBoostEntity? = repository.findById(id)

    fun delete(id: String) {
        repository.deleteById(id)
    }
}
