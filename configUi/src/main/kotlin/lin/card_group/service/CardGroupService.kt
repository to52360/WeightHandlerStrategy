package lin.card_group.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import lin.card_group.domain.CardBindingEntity
import lin.card_group.domain.CardManagerEntity
import lin.card_group.repository.CardGroupRepository
import lin.rule.tree.CardGroupBinding
import lin.rule.tree.CardGroupManagerConfig
import java.util.*

// 由于 sourceFile 移到了 Manager，Binding 不再需要独立的 Draft/View 包装，直接使用领域对象 CardGroupBinding 即可。

class CardGroupService(private val repository: CardGroupRepository) {

    private val mapper = jacksonObjectMapper()

    // ─────────────────────── Manager ───────────────────────────────────────

    /** 加载所有 Manager，每个 Manager 内嵌其 Binding 列表 */
    fun loadAll(): List<CardGroupManagerConfig> {
        return repository.findAllManagers().map { managerEntity ->
            val bindings = repository.findBindingsByManager(managerEntity.id)
                .map { it.toDomain() }
            managerEntity.toDomain(bindings)
        }
    }

    fun saveManager(
        name: String,
        sourceFile: String,
        enabled: Boolean,
        bindings: List<CardGroupBinding>,
        existingId: String? = null
    ): String {
        val id = existingId ?: UUID.randomUUID().toString().substring(0, 8)
        repository.saveManager(CardManagerEntity(id = id, name = name, sourceFile = sourceFile, enabled = enabled))

        // 整体替换该 Manager 下的 Binding
        val entities = bindings.map { binding ->
            CardBindingEntity(
                id = binding.id,
                mangerId = id,
                name = binding.name,
                cardIds = mapper.writeValueAsString(binding.cardIds)
            )
        }
        repository.replaceBindings(id, entities)
        return id
    }

    fun deleteManager(id: String) = repository.deleteManager(id)

    /** 加载某 Manager 下所有 Binding（供 UI 工作台使用） */
    fun loadBindings(managerId: String): List<CardGroupBinding> =
        repository.findBindingsByManager(managerId).map { it.toDomain() }

    /** 仅加载 Manager 摘要列表（id/name/enabled），不级联加载 Binding，供左侧列表刷新 */
    fun loadAllManagers(): List<CardManagerEntity> = repository.findAllManagers()


    // ─────────────────────── 单条 Binding ──────────────────────────────────

    fun saveBinding(binding: CardGroupBinding) =
        repository.saveBinding(
            CardBindingEntity(
                id = binding.id,
                mangerId = binding.mangerId,
                name = binding.name,
                cardIds = mapper.writeValueAsString(binding.cardIds)
            )
        )

    fun deleteBinding(id: String) =
        repository.deleteBinding(id)

    // ─────────────────────── 转换工具 ──────────────────────────────────────

    private fun CardManagerEntity.toDomain(bindings: List<CardGroupBinding>) =
        CardGroupManagerConfig(
            cardGroupMangerId = id,
            name = name,
            bindings = bindings,
            enabled = enabled
        )
}
