package lin.repository.card_group

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import lin.bean.usePlan.GroupUseOverride
import lin.rule.tree.CardGroupBehavior
import lin.rule.tree.CardGroupBehavior.*
import lin.rule.tree.CardGroupBinding
import lin.rule.tree.CardGroupManagerConfig
import java.util.*

// 由于 sourceFile 移到了 Manager，Binding 不再需要独立的 Draft/View 包装，直接使用领域对象 CardGroupBinding 即可。

class CardGroupService(private val repository: CardGroupRepository) {

    private val mapper = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    // ─────────────────────── Manager ───────────────────────────────────────

    /** 加载 Manager，每个 Manager 内嵌其 Binding 列表 */
    fun loadAll(onlyEnabled: Boolean = false): List<CardGroupManagerConfig> {
        return repository.findManagers(onlyEnabled).map { managerEntity ->
            val bindings = loadBindingsWithOverrides(managerEntity.id)
            managerEntity.toDomain(bindings)
        }
    }

    /**
     * 加载某 Manager 下的 Binding，并将行为（OVERRIDE / USE_ACTION）从 card_group_behavior 表合并进来。
     * 行为覆盖与使用动作均只存于 card_group_behavior 表，主表 card_group_binding 不再持有 overrides 列。
     */
    private fun loadBindingsWithOverrides(managerId: String): List<CardGroupBinding> {
        val bindingEntities = repository.findBindingsByManager(managerId)
        val overrideByBinding = repository.findBehaviorsByManager(managerId, GroupBehaviorType.OVERRIDE)
            .associate { it.bindingId to mapper.readValue<GroupUseOverride>(it.payload) }
        val useActionByBinding = repository.findBehaviorsByManager(managerId, GroupBehaviorType.USE_ACTION)
            .associate { it.bindingId to parseUseActionPayload(it.payload) }
        val surplusGateByBinding = repository.findBehaviorsByManager(managerId, GroupBehaviorType.SURPLUS_GATE)
            .associate { it.bindingId to SurplusGateBehavior(mapper.readValue<SurplusGatePayload>(it.payload).idleThreshold) }
        return bindingEntities.map { entity ->
            val behaviors = buildList {
                overrideByBinding[entity.id]?.let { add(OverrideBehavior(it)) }
                useActionByBinding[entity.id]?.let { add(it) }
                surplusGateByBinding[entity.id]?.let { add(it) }
            }
            entity.toDomain(behaviors)
        }
    }

    fun saveManager(
        name: String,
        sourceFile: String,
        enabled: Boolean,
        bindings: List<CardGroupBinding>,
        existingId: String? = null,
        managerDescription: String? = null,
        managerStatus: String? = null
    ): String {
        val allManagers = repository.findAllManagers()
        val id = existingId
            ?: allManagers.firstOrNull { it.name == name || it.sourceFile == sourceFile }?.id
            ?: UUID.randomUUID().toString().substring(0, 8)

        // 清理同名/同源文件的重复 Manager
        allManagers.filter { (it.name == name || it.sourceFile == sourceFile) && it.id != id }
            .forEach { repository.deleteManager(it.id) }

        // 缺省（null/blank）保持原值不覆盖——修复 2026-08-10 待测发现：
        // 此前 null 直接写入 upsert 会清空已有 description/status，与工具描述"缺省不覆盖"不符。
        val existingMeta = allManagers.firstOrNull { it.id == id }
        repository.saveManager(
            CardManagerEntity(
                id = id,
                name = name,
                sourceFile = sourceFile,
                enabled = enabled,
                description = managerDescription?.takeIf { it.isNotBlank() } ?: existingMeta?.description,
                status = managerStatus?.takeIf { it.isNotBlank() } ?: existingMeta?.status
            )
        )

        // 整体替换该 Manager 下的 Binding（行为覆盖/使用动作已迁到 card_group_behavior 表）
        val entities = bindings.map { binding ->
            CardBindingEntity(
                id = binding.id,
                managerId = id,
                name = binding.name,
                cardIds = mapper.writeValueAsString(binding.cardIds),
                description = binding.description
            )
        }
        repository.replaceBindings(id, entities)

        // 覆盖写 card_group_behavior：先清该 Manager 全部旧行为行，再按当前 Binding 重填
        repository.deleteBehaviorsByManager(id)
        bindings.forEach { binding ->
            binding.behaviors.forEach { b ->
                toBehaviorEntity(binding.id, b)?.let { repository.saveBehavior(it) }
            }
        }
        return id
    }

    fun deleteManager(id: String) = repository.deleteManager(id)

    /**
     * 仅更新方案级元信息（description/status），不触碰 bindings。
     * 供频繁的状态/描述变更（细粒度 MCP 工具 update_card_group_meta），
     * 避免走 saveManager 的整体 replaceBindings 重建。缺省（null/blank）保持原值不覆盖。
     */
    fun updateManagerMeta(managerId: String, description: String?, status: String?): Boolean {
        val existing = repository.findManagerById(managerId) ?: return false
        repository.updateManagerMeta(
            managerId,
            description = description?.takeIf { it.isNotBlank() } ?: existing.description,
            status = status?.takeIf { it.isNotBlank() } ?: existing.status
        )
        return true
    }

    /** 加载某 Manager 下所有 Binding（供 UI 工作台使用） */
    fun loadBindings(managerId: String): List<CardGroupBinding> =
        loadBindingsWithOverrides(managerId)

    /** 仅加载 Manager 摘要列表（id/name/enabled），不级联加载 Binding，供左侧列表刷新 */
    fun loadAllManagers(): List<CardManagerEntity> = repository.findAllManagers()


    // ─────────────────────── 单条 Binding ──────────────────────────────────

    fun saveBinding(binding: CardGroupBinding) {
        repository.saveBinding(
            CardBindingEntity(
                id = binding.id,
                managerId = binding.managerId,
                name = binding.name,
                cardIds = mapper.writeValueAsString(binding.cardIds),
                description = binding.description
            )
        )
        // 覆盖写 card_group_behavior：先清该 Binding 全部旧行为行，再按当前行为列表重填
        repository.deleteBehaviorsByBinding(binding.id)
        binding.behaviors.forEach { b ->
            toBehaviorEntity(binding.id, b)?.let { repository.saveBehavior(it) }
        }
    }

    fun deleteBinding(id: String) =
        repository.deleteBinding(id)

    // ─────────────────────── 分组行为 ──────────────────────────────────────

    /** 加载某 Binding 下的所有行为行 */
    fun loadBehaviors(bindingId: String): List<CardGroupBehaviorEntity> =
        repository.findBehaviorsByBinding(bindingId)

    /** 替换某 Binding 下的所有行为行 */
    fun saveBehaviors(bindingId: String, behaviors: List<CardGroupBehaviorEntity>) {
        repository.replaceBehaviors(bindingId, behaviors)
    }

    /** 加载全部 USE_ACTION 行为，聚合成 bindingId → UseActionBehavior 列表 */
    fun loadAllUseActions(): Map<String, List<String>> =
        repository.findBehaviorsByType(GroupBehaviorType.USE_ACTION)
            .associate { it.bindingId to parseUseActionPayload(it.payload).useActions }

    // ─────────────────────── 序列化工具 ──────────────────────────────────────

    /** 领域行为 → card_group_behavior 实体行；无需持久化的（default 覆盖 / 空 useActions）返回 null。 */
    private fun toBehaviorEntity(bindingId: String, b: CardGroupBehavior): CardGroupBehaviorEntity? = when (b) {
        is OverrideBehavior -> if (b.override.isDefault()) null
        else CardGroupBehaviorEntity(bindingId, GroupBehaviorType.OVERRIDE, mapper.writeValueAsString(b.override))

        is UseActionBehavior -> if (b.useActions.isEmpty()) null
        else CardGroupBehaviorEntity(bindingId, GroupBehaviorType.USE_ACTION, toUseActionPayload(b))

        is SurplusGateBehavior ->
            CardGroupBehaviorEntity(bindingId, GroupBehaviorType.SURPLUS_GATE, toSurplusGatePayload(b))
    }

    /** 反序列化 USE_ACTION 的 payload，兼容旧格式（纯 List<String>）和新格式（{useActions, extraConfig}）。 */
    @Suppress("UNCHECKED_CAST")
    private fun parseUseActionPayload(payload: String): UseActionBehavior {
        return try {
            val map = mapper.readValue<Map<String, Any>>(payload)
            UseActionBehavior(
                useActions = (map["useActions"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
                extraConfig = map["extraConfig"] as? Map<String, Any> ?: emptyMap()
            )
        } catch (_: Exception) {
            UseActionBehavior(useActions = mapper.readValue(payload))
        }
    }

    /** 序列化 USE_ACTION 为 JSON payload */
    private fun toUseActionPayload(b: UseActionBehavior): String =
        mapper.writeValueAsString(mapOf("useActions" to b.useActions, "extraConfig" to b.extraConfig))

    /** SURPLUS_GATE 的 payload 反序列化载体（sealed 子类不作为 Jackson 目标类型，避免 sealed 多态解析）。 */
    private data class SurplusGatePayload(val idleThreshold: Int)

    /** 序列化 SURPLUS_GATE 为 JSON payload */
    private fun toSurplusGatePayload(b: SurplusGateBehavior): String =
        mapper.writeValueAsString(mapOf("idleThreshold" to b.idleThreshold))

    // ─────────────────────── 转换工具 ──────────────────────────────────────

    private fun CardManagerEntity.toDomain(bindings: List<CardGroupBinding>) =
        CardGroupManagerConfig(
            cardGroupManagerId = id,
            name = name,
            bindings = bindings,
            enabled = enabled
        )
}
