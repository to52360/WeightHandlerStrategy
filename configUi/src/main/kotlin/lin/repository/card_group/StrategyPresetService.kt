package lin.repository.card_group

import lin.repository.delete_snapshot.*
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.*

/** 预设保存结果。 */
data class SavePresetResult(
    val presetId: String,
    val name: String,
    val treeItemCount: Int,
    val timingCount: Int,
    /** T-TG-029：惜售声明条数（独立维度）。 */
    val surplusCount: Int
)

/** 预设详情（**三个维度**的完整内容）。 */
data class PresetDetail(
    val preset: StrategyPresetEntity,
    /** 用途 → 保留的树 id 集合（**缺席的用途 = 未声明 ⇒ 该用途树全禁**）。 */
    val treeSelections: Map<String, Set<String>>,
    val timings: Map<String, TimingOverride>,
    /** 用途 → 惜售门槛声明（T-TG-029；缺席 = 未声明 ⇒ 回落全局行）。 */
    val surplus: Map<String, SurplusOverride>
)

/** 预设的引用者。 */
data class PresetReference(val managerId: String, val managerName: String)

/** 预设列表项（含维度计数与引用者 —— 引用者用于识别"卡组专用预设"）。 */
data class PresetSummary(
    val preset: StrategyPresetEntity,
    val treeItemCount: Int,
    val timingCount: Int,
    /** T-TG-029：惜售声明条数（独立维度）。 */
    val surplusCount: Int,
    val referencedBy: List<PresetReference>
)

/** 卡组增量项（③微调层）。 */
data class DeckDelta(
    val managerId: String,
    /** 用途 → 额外排除的树 id 集合。 */
    val treeExclusions: Map<String, Set<String>>,
    val timings: Map<String, TimingOverride>,
    /** 用途 → 惜售门槛声明（T-TG-029；与预设逐项覆盖）。 */
    val surplus: Map<String, SurplusOverride> = emptyMap(),
    /**
     * **本卡组不使用**的用途（消费侧「去除」通道；**整用途退出**视图，D-TG-020）。
     *
     * 语义 = 这些用途**整体退出本卡组**：① 从本卡组的规则集合里减掉 ⇒ **无规则**
     * （**不回落全局默认值**）；② 其**用途树**（全局共享 + 专属）一并停用。
     * ⚠️ 与 `treeExclusions` 是**粗 / 细两层**：本项 = 用途级；树排除 = 挑掉某几棵树。
     */
    val excludedPurposes: Set<String> = emptySet(),
    /**
     * **维度级排除**的完整映射（D-TG-021）：`用途 → 被禁维度集合`。
     *
     * `excludedPurposes` 只是本字段 { **TREE,TIMING,SURPLUS 全命中** 的用途 } 的便捷视图；
     * 一个用途若**只禁部分维度**，只会出现在本字段、不会进 [excludedPurposes]。
     */
    val exclusionDimensions: Map<String, Set<String>> = emptyMap()
)

/**
 * 用途预设服务（T-TG-015）—— 方案见 `cross-dialogue/Q-TG-003-use-preset-final.md`。
 *
 * **定位**：预设 = 兜底层（按用途声明「保留哪些树 + 时序」，打标签自动受益）；
 * 卡组增量项 = 微调层（在预设之上**只减 + 覆盖**）。
 *
 * 两个维度共用一张 `strategy_dimension_item`（`scope` 区分预设项 / 卡组项），
 * 解析在**配置侧**（引擎零改动）：解析链 `卡组私有 > 消费方增量项 > 预设 > 全局用途规则`。
 * 「不用预设」= `card_group_manager.preset_id` 为空 —— **零开关**。
 */
class StrategyPresetService(
    private val presetRepository: StrategyPresetRepository,
    private val groupRepository: CardGroupRepository,
    private val tx: TransactionTemplate
) {

    // ─────────────────── 删除 + 快照 / 恢复（T-TG-021：业务归域 + 机制由快照域编排）───────────────────

    /** 导出本资源的删除操作值（锚 + 两维度项）。**仍被卡组引用时拒绝**（不提供隐式清引用路径，见 D-TG-010）。 */
    fun deleteOps(): SnapshotOps = SnapshotOps(
        collect = { entityId ->
            val preset = presetRepository.findPresetById(entityId)
                ?: throw SnapshotRefused("预设不存在: $entityId")
            val references = findReferences(entityId)
            if (references.isNotEmpty()) {
                val refInfo = references.joinToString("\n") { "  - ${it.managerName} (id=${it.managerId})" }
                throw SnapshotRefused(
                    "无法删除预设 [${preset.name}] (id=$entityId)，以下卡组引用此预设:\n$refInfo\n" +
                            "请先用 save_card_group_preset（不带 presetId）解除这些卡组的引用后重试。"
                )
            }
            SnapshotDraft(
                entityName = preset.name,
                payload = SnapshotPayloads.strategyPreset(
                    StrategyPresetSnapshot(
                        preset = preset,
                        dimensionItems = presetRepository.findItems(DimensionScope.PRESET, entityId)
                    )
                ),
                echo = mapOf("deleted" to entityId, "name" to preset.name)
            )
        },
        remove = { entityId -> deletePreset(entityId) }
    )

    /** 按快照写回（锚 + 两维度项按维度覆盖写回）；原 id 已被占用则拒绝。 */
    fun restoreFromSnapshot(id: String, payload: String): RestoreResult {
        presetRepository.findPresetById(id)?.let { occupied ->
            return RestoreResult(
                "恢复失败：原 id=$id 已被现有数据占用（现有名称: ${occupied.name}）。请先删除/改名现有数据再恢复",
                isError = true
            )
        }
        val p = SnapshotPayloads.mapper.readValue(payload, StrategyPresetSnapshot::class.java)
        return tx.execute {
            presetRepository.savePreset(p.preset.copy(id = id))
            p.dimensionItems.groupBy { it.dimension }.forEach { (dimension, items) ->
                presetRepository.replaceItems(DimensionScope.PRESET, id, dimension, items)
            }
            RestoreResult(
                "已恢复 strategy_preset $id（含 ${p.dimensionItems.size} 条维度项，原 id 保留）",
                isError = false
            )
        }!!
    }

    /**
     * 新建或更新预设。
     *
     * @param presetId null = 新建（自动生成 id）；非 null = 更新（须已存在）
     * @param treeSelections null = **不修改**树选择；非 null = **整体替换**
     *   （`用途 → 保留的树 id`；某用途出现但集合为空 = 该用途保留零棵树；**未出现的用途 = 未声明 ⇒ 全禁**）
     * @param timings null = 不修改；非 null = 整体替换
     * @param surplus null = 不修改；非 null = 整体替换（T-TG-029 惜售维度）
     * @return null = 更新目标不存在
     */
    fun savePreset(
        presetId: String?,
        name: String,
        description: String?,
        treeSelections: Map<String, Collection<String>>?,
        timings: Map<String, TimingOverride>?,
        surplus: Map<String, SurplusOverride>? = null
    ): SavePresetResult? = tx.execute {
        val id = presetId ?: UUID.randomUUID().toString().substring(0, 8)
        val existing = presetRepository.findPresetById(id)
        if (presetId != null && existing == null) return@execute null

        presetRepository.savePreset(
            StrategyPresetEntity(
                id = id,
                name = name,
                description = description,
                createdAt = existing?.createdAt ?: Instant.now().toString()
            )
        )
        treeSelections?.let { presetRepository.replaceTreeSelections(DimensionScope.PRESET, id, it) }
        timings?.let { presetRepository.replaceTimings(DimensionScope.PRESET, id, it) }
        surplus?.let { presetRepository.replaceSurplus(DimensionScope.PRESET, id, it) }

        SavePresetResult(
            presetId = id,
            name = name,
            treeItemCount = presetRepository.findTreeSelections(DimensionScope.PRESET, id).values.sumOf { it.size },
            timingCount = presetRepository.findTimings(DimensionScope.PRESET, id).size,
            surplusCount = presetRepository.findSurplus(DimensionScope.PRESET, id).size
        )
    }

    /**
     * 从现有预设**派生**新预设（fork，D-TG-017）—— 用于「快攻通用 / 控制通用」这类**分类通用预设**的快速建站。
     *
     * 复制的是**内容**而非**关系**：源预设的 `PURPOSE_TREE` / `PURPOSE_TIMING` 两个维度**全量写入新 id**，
     * 此后两者**各自独立演化**（改源预设不传导到已派生的预设 —— 不引入 `base_preset_id` 继承，见 D-TG-017）。
     *
     * ⚠️ 源预设**漏声明的用途**会被一并继承（未声明 = 该用途树全禁）⇒ 调用方须核对 `disabledPurposes`。
     *
     * @return null = 源预设不存在
     */
    fun clonePreset(sourceId: String, name: String, description: String? = null): SavePresetResult? = tx.execute {
        val source = presetRepository.findPresetById(sourceId) ?: return@execute null

        val newId = UUID.randomUUID().toString().substring(0, 8)
        presetRepository.savePreset(
            StrategyPresetEntity(
                id = newId,
                name = name,
                description = description ?: source.description,
                createdAt = Instant.now().toString()
            )
        )
        val trees = presetRepository.findTreeSelections(DimensionScope.PRESET, sourceId)
        val timings = presetRepository.findTimings(DimensionScope.PRESET, sourceId)
        val surplus = presetRepository.findSurplus(DimensionScope.PRESET, sourceId)
        presetRepository.replaceTreeSelections(DimensionScope.PRESET, newId, trees)
        presetRepository.replaceTimings(DimensionScope.PRESET, newId, timings)
        presetRepository.replaceSurplus(DimensionScope.PRESET, newId, surplus)

        SavePresetResult(
            presetId = newId,
            name = name,
            treeItemCount = trees.values.sumOf { it.size },
            timingCount = timings.size,
            surplusCount = surplus.size
        )
    }

    fun findDetail(presetId: String): PresetDetail? {
        val preset = presetRepository.findPresetById(presetId) ?: return null
        return PresetDetail(
            preset = preset,
            treeSelections = presetRepository.findTreeSelections(DimensionScope.PRESET, presetId),
            timings = presetRepository.findTimings(DimensionScope.PRESET, presetId),
            surplus = presetRepository.findSurplus(DimensionScope.PRESET, presetId)
        )
    }

    fun listPresets(): List<PresetSummary> {
        val allManagers = groupRepository.findManagers()
        val refsByPreset = allManagers
            .filter { !it.presetId.isNullOrBlank() }
            .groupBy({ it.presetId!! }, { PresetReference(it.id, it.name) })
        return presetRepository.findAllPresets().map { preset ->
            val trees = presetRepository.findTreeSelections(DimensionScope.PRESET, preset.id)
            PresetSummary(
                preset = preset,
                treeItemCount = trees.values.sumOf { it.size },
                timingCount = presetRepository.findTimings(DimensionScope.PRESET, preset.id).size,
                surplusCount = presetRepository.findSurplus(DimensionScope.PRESET, preset.id).size,
                referencedBy = refsByPreset[preset.id].orEmpty()
            )
        }
    }

    /** 引用某预设的卡组（用于识别"卡组专用预设"）。 */
    fun findReferences(presetId: String): List<PresetReference> =
        groupRepository.findManagersByPresetId(presetId)
            .map { PresetReference(it.id, it.name) }

    fun deletePreset(presetId: String): StrategyPresetEntity? {
        val preset = presetRepository.findPresetById(presetId) ?: return null
        presetRepository.deletePreset(presetId)
        return preset
    }

    /**
     * 卡组选预设（`presetId` 为 null/空白 = **不用预设**）。
     *
     * ⚠️ 声明模型（D-TG-018）下"不用预设" = **一条声明都没有** ⇒ 该卡组的**全局用途树与时序规则都不生效**
     * （旧文案"不用预设 = 全部用途树生效"已作废；卡组专属用途树不受影响，建树即声明）。
     *
     * @return 失败原因文案；null = 成功
     */
    fun setCardGroupPreset(managerId: String, presetId: String?): String? {
        if (groupRepository.findManagerById(managerId) == null) {
            return "卡组不存在: $managerId"
        }
        val target = presetId?.takeIf { it.isNotBlank() }
        if (target != null && presetRepository.findPresetById(target) == null) {
            return "预设不存在: $target"
        }
        groupRepository.updateManagerPreset(managerId, target)
        return null
    }
 
    // ─────────────────────── 卡组增量项（③微调层） ───────────────────────
 
    /** 读卡组增量项；无卡组 / 无项时返回空集。 */
    fun findDeckDelta(managerId: String): DeckDelta {
        val exclusions = presetRepository.findExclusions(DimensionScope.CARD_GROUP, managerId)
        return DeckDelta(
            managerId = managerId,
            treeExclusions = presetRepository.findTreeSelections(DimensionScope.CARD_GROUP, managerId),
            timings = presetRepository.findTimings(DimensionScope.CARD_GROUP, managerId),
            surplus = presetRepository.findSurplus(DimensionScope.CARD_GROUP, managerId),
            excludedPurposes = exclusions.filterValues { it == Dimension.EXCLUDABLE_DIMENSIONS }.keys,
            exclusionDimensions = exclusions
        )
    }

    /**
     * 写卡组增量项（**整体替换**语义：各项为 null = 不改，空集合 = 清空）。
     *
     * ⚠️ 与 [setCardGroupPreset] 分开：只改增量项，不动预设引用。
     *
     * @param surplus T-TG-029 惜售维度（独立替换；null = 不改）
     * @param excludedPurposes 消费侧「去除」通道（**全禁简写**，D-TG-020）：这些用途**整用途退出**（独立替换；null = 不改）
     * @param exclusionDimensions **维度级排除**完整映射（D-TG-021）：`用途 → 被禁维度集合`（优先级高于 [excludedPurposes]；
     *   null = 不改）。两侧都传时，[exclusionDimensions] 表里的用途以其维度集为准，其余用 [excludedPurposes] 的全禁兜底。
     * @return 失败原因文案；null = 成功
     */
    fun saveDeckDelta(
        managerId: String,
        treeExclusions: Map<String, Collection<String>>?,
        timings: Map<String, TimingOverride>?,
        surplus: Map<String, SurplusOverride>? = null,
        excludedPurposes: Set<String>? = null,
        exclusionDimensions: Map<String, Set<String>>? = null
    ): String? = tx.execute {
        if (groupRepository.findManagerById(managerId) == null) {
            return@execute "卡组不存在: $managerId"
        }
        treeExclusions?.let {
            presetRepository.replaceTreeSelections(DimensionScope.CARD_GROUP, managerId, it)
        }
        timings?.let { presetRepository.replaceTimings(DimensionScope.CARD_GROUP, managerId, it) }
        surplus?.let { presetRepository.replaceSurplus(DimensionScope.CARD_GROUP, managerId, it) }

        // 排除通道两种入参合并成一张「用途 → 被禁维度」表：维度级优先，全禁简写填充其余。
        if (exclusionDimensions != null || excludedPurposes != null) {
            val merged = buildMap {
                exclusionDimensions?.forEach { (tag, dims) -> put(tag, dims) }
                excludedPurposes?.forEach { tag -> put(tag, Dimension.EXCLUDABLE_DIMENSIONS) }
            }
            presetRepository.replaceExclusions(DimensionScope.CARD_GROUP, managerId, merged)
        }
        null
    }
}
