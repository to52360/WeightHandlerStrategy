package lin.card_group.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import lin.card_group.domain.CardBindingEntity
import lin.card_group.domain.CardManagerEntity
import lin.card_group.repository.CardGroupRepository
import lin.rule.tree.CardGroupBinding
import lin.rule.tree.CardGroupManagerConfig
import java.util.*

/**
 * UI 层向 Service 传入的 Binding 草稿，携带 sourceFile（持久化需要，业务层不感知）。
 */
data class BindingDraft(
    val binding: CardGroupBinding,
    val sourceFile: String   // 来源 .cardgroup 文件名（不含扩展名）
)

/** UI 读取时携带 sourceFile 的视图对象（业务层 CardGroupBinding 不感知 sourceFile） */
data class BindingView(
    val binding: CardGroupBinding,
    val sourceFile: String
)

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

    /**
     * 保存 Manager 及其 Binding 列表。
     * [existingId] 为 null 时新建（生成 UUID），否则执行 UPSERT。
     * 返回最终使用的 managerId。
     */
    fun saveManager(
        name: String,
        enabled: Boolean,
        drafts: List<BindingDraft>,
        existingId: String? = null
    ): String {
        val id = existingId ?: UUID.randomUUID().toString().substring(0, 8)
        repository.saveManager(CardManagerEntity(id = id, name = name, enabled = enabled))

        // 整体替换该 Manager 下的 Binding，使用已有的 id 或者新生成
        val entities = drafts.map { draft ->
            CardBindingEntity(
                id = draft.binding.id,
                mangerId = id,
                sourceFile = draft.sourceFile,
                name = draft.binding.name,
                cardIds = mapper.writeValueAsString(draft.binding.cardIds)
            )
        }
        repository.replaceBindings(id, entities)
        return id
    }

    fun deleteManager(id: String) = repository.deleteManager(id)

    /** 加载某 Manager 下所有 Binding，含 sourceFile（供 UI 工作台使用） */
    fun loadBindingViews(managerId: String): List<BindingView> =
        repository.findBindingsByManager(managerId).map { entity ->
            BindingView(
                binding = entity.toDomain(),
                sourceFile = entity.sourceFile
            )
        }

    /** 仅加载 Manager 摘要列表（id/name/enabled），不级联加载 Binding，供左侧列表刷新 */
    fun loadAllManagers(): List<CardManagerEntity> = repository.findAllManagers()


    // ─────────────────────── 单条 Binding ──────────────────────────────────

    fun saveBinding(draft: BindingDraft) =
        repository.saveBinding(
            CardBindingEntity(
                id = draft.binding.id,
                mangerId = draft.binding.mangerId,
                sourceFile = draft.sourceFile,
                name = draft.binding.name,
                cardIds = mapper.writeValueAsString(draft.binding.cardIds)
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
