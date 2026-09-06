package lin.repository.aura_boost

import com.fasterxml.jackson.databind.ObjectMapper
import lin.repository.condition_tree.ConditionTreeConfigService
import lin.repository.condition_tree.resolveConditionTreeReference
import org.springframework.transaction.support.TransactionTemplate
import java.util.*

/**
 * Push 广播评分配置（aura-boost D-001/D-002）持久层模型。
 *
 * @param managerId 消费方归属（D-003 分层）：aura_boost 是卡组级配置（圣契卡组才有莱妮莎光环），
 *                  归属在消费方，引用的条件树（condition_id/target_condition_id）是全局资源。
 */
data class AuraBoostEntity(
    val id: String,
    val name: String?,
    val conditionId: String,
    val targetConditionId: String,
    val score: Double,
    val managerId: String?
)

data class SaveAuraBoostInput(
    val name: String? = null,
    val conditionId: String,
    val targetConditionId: String,
    val score: Double,
    val managerId: String? = null,
    val existingId: String? = null
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
    val existingId: String? = null
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
    fun save(input: SaveAuraBoostInput): String {
        val id = input.existingId ?: UUID.randomUUID().toString().substring(0, 8)
        repository.save(
            AuraBoostEntity(
                id = id,
                name = input.name,
                conditionId = input.conditionId,
                targetConditionId = input.targetConditionId,
                score = input.score,
                managerId = input.managerId
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
                existingId = input.existingId
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
