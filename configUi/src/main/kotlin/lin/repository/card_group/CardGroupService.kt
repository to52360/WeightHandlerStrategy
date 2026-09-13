package lin.repository.card_group

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import lin.bean.usePlan.GroupUseOverride
import lin.config.PathConfig
import lin.dao.CardGroupConfig
import lin.dao.CardGroupJsonParser
import lin.repository.delete_snapshot.*
import lin.rule.tree.CardGroupBehavior
import lin.rule.tree.CardGroupBehavior.*
import lin.rule.tree.CardGroupBinding
import lin.rule.tree.CardGroupManagerConfig
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Files
import java.util.*

// 由于 sourceFile 移到了 Manager，Binding 不再需要独立的 Draft/View 包装，直接使用领域对象 CardGroupBinding 即可。

/**
 * [CardGroupService.saveManager] 的入参载体（manager 级保存命令）。
 * 参数原平铺在方法签名上，随字段增加持续膨胀（8 个），封装收敛；
 * 可空字段 null = 缺省不覆盖已有值（见 saveManager 内「保持原值」逻辑）。
 */
data class ManagerSaveCommand(
    val name: String,
    val sourceFile: String,
    val enabled: Boolean,
    val bindings: List<CardGroupBinding>,
    val existingId: String? = null,
    val managerDescription: String? = null,
    val managerStatus: String? = null,
    val defaultIncludeDerived: Boolean? = null
)

class CardGroupService(
    private val repository: CardGroupRepository,
    /** T-008：多表 / 多步写的事务边界（Koin 非 Spring 容器，注解式事务不生效，手动包裹）。 */
    private val tx: TransactionTemplate
) {

    // ─────────────────── 卡池（文件资源，同属卡组域）删除 + 快照 / 恢复（T-TG-021）───────────────────

    /** 导出 card_pool 的删除操作值；文件不存在 / 有 card_group 依赖时抛 [SnapshotRefused]（不落快照、不删）。 */
    fun deleteOps(): SnapshotOps = SnapshotOps(
        collect = { entityId -> collectCardPoolSnapshot(entityId) },
        remove = { entityId -> Files.delete(PathConfig.defaultDirPath.resolve("$entityId.cardgroup")) }
    )

    private fun collectCardPoolSnapshot(entityId: String): SnapshotDraft {
        if (entityId.isBlank()) throw SnapshotRefused("fileName 参数不能为空")
        val file = PathConfig.defaultDirPath.resolve("$entityId.cardgroup")
        if (!Files.exists(file)) throw SnapshotRefused("卡池文件不存在: $entityId.cardgroup")

        // 依赖校验：有 card_group 引用此卡池时拒绝
        val dependents = loadAllManagers().filter { it.sourceFile == entityId }
        if (dependents.isNotEmpty()) {
            val depInfo = dependents.joinToString("\n") { mgr -> "  - ${mgr.name} (id=${mgr.id})" }
            throw SnapshotRefused(
                "无法删除 $entityId.cardgroup，以下卡牌分组方案依赖此卡池:\n$depInfo\n" +
                        "请先删除这些方案（delete resource=card_group）或将其 sourceFile 改为其他卡池后重试。"
            )
        }

        val config = CardGroupJsonParser.loadByFileName(entityId)
            ?: throw SnapshotRefused("卡池文件不存在或解析失败: $entityId.cardgroup")
        return SnapshotDraft(
            entityName = entityId,
            payload = SnapshotPayloads.cardPool(config),
            echo = mapOf(
                "deleted" to true,
                "fileName" to entityId,
                "filePath" to file.toString()
            )
        )
    }

    /** 按快照重建 `.cardgroup` 文件；原文件已存在则拒绝。 */
    fun restoreCardPoolFromSnapshot(fileName: String, payload: String): RestoreResult {
        if (Files.exists(PathConfig.defaultDirPath.resolve("$fileName.cardgroup"))) {
            return RestoreResult(
                "恢复失败：原文件 $fileName.cardgroup 已存在。请先删除/改名现有数据再恢复",
                isError = true
            )
        }
        val config = SnapshotPayloads.mapper.readValue(payload, CardGroupConfig::class.java)
        CardGroupJsonParser.saveCardGroupConfigs(config.cards, fileName, config.enabled)
        return RestoreResult("已恢复 card_pool $fileName.cardgroup（原文件重建，含 cards 权重）", isError = false)
    }

    /** 单独写回卡组的预设引用（`saveManager` 有意不写 `preset_id`，见 T-TG-010 卡组恢复）。 */
    fun setPresetReference(managerId: String, presetId: String?) {
        repository.updateManagerPreset(managerId, presetId)
    }

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

    /**
     * T-008：多表多步写——manager → 删同名 / 同源旧 manager → `replaceBindings`
     * （**先 `DELETE` 再逐条 insert**）→ 重写 behaviors。任一步失败都会留半写入脏状态
     * （最坏：删了旧的、新的没插全），故整体包事务。
     */
    fun saveManager(command: ManagerSaveCommand): String = tx.execute {
        val (name, sourceFile, enabled, bindings) = command
        val allManagers = repository.findAllManagers()
        val id = command.existingId
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
                description = command.managerDescription?.takeIf { it.isNotBlank() } ?: existingMeta?.description,
                status = command.managerStatus?.takeIf { it.isNotBlank() } ?: existingMeta?.status,
                // 未显式传入时保持原值（null 表"未声明"，不应用 null 覆盖已有设置）
                defaultIncludeDerived = command.defaultIncludeDerived ?: existingMeta?.defaultIncludeDerived
            )
        )

        // 单活卡组（@defect open-questions/Q-OQ-005）：引擎侧按「**所有 enabled 卡组**」加载配置
        // （`SqliteAuraBoostConfigProvider` / TreeConfigProvider 等均如此），**无「当前卡组」概念**
        // → 多卡组同时 enabled 会串味（绑 A 的 AuraBoost 在玩 B 时照样生效）。
        // 结构性消除：启用本卡组时自动禁用其余卡组，把「同时只启用一个」从**约定**变成**机制保证**。
        // ⚠️ 已知缺陷：UI 侧不会主动刷新其它卡组的启用态（需重新加载才看到已置灰）——可接受，记录待收敛。
        if (enabled) {
            allManagers.filter { it.id != id && it.enabled }
                .forEach { repository.saveManager(it.copy(enabled = false)) }
        }

        // 整体替换该 Manager 下的 Binding（行为覆盖/使用动作已迁到 card_group_behavior 表）
        val entities = bindings.map { CardBindingEntity.fromDomain(it.copy(managerId = id)) }
        repository.replaceBindings(id, entities)

        // 覆盖写 card_group_behavior：先清该 Manager 全部旧行为行，再按当前 Binding 重填
        repository.deleteBehaviorsByManager(id)
        bindings.forEach { binding ->
            binding.behaviors.forEach { b ->
                toBehaviorEntity(binding.id, b)?.let { repository.saveBehavior(it) }
            }
        }
        id
    }!!

    /** T-008：级联删（behaviors → bindings → manager）三步，包事务防半删。 */
    fun deleteManager(id: String) {
        tx.execute { repository.deleteManager(id) }
    }

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

    /** T-008：binding + behaviors 两步写（先删后填），包事务防"binding 改了、behaviors 没跟上"。 */
    fun saveBinding(binding: CardGroupBinding) {
        tx.execute {
            repository.saveBinding(CardBindingEntity.fromDomain(binding))
            // 覆盖写 card_group_behavior：先清该 Binding 全部旧行为行，再按当前行为列表重填
            repository.deleteBehaviorsByBinding(binding.id)
            binding.behaviors.forEach { b ->
                toBehaviorEntity(binding.id, b)?.let { repository.saveBehavior(it) }
            }
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
            enabled = enabled,
            // P0-2 修复（2026-08-31 审查）：此前漏传 → 写入端能存、读出恒 null，
            // 两级覆盖链（组级 > 卡组级 > false）中间层在读路径断裂。
            defaultIncludeDerived = defaultIncludeDerived
        )
}
