package lin.repository.aura_boost

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

class AuraBoostConfigService(
    private val repository: AuraBoostRepository
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

    fun loadAll(): List<AuraBoostEntity> = repository.findAll()

    fun findById(id: String): AuraBoostEntity? = repository.findById(id)

    fun delete(id: String) {
        repository.deleteById(id)
    }
}
