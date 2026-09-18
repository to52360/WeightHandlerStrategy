package lin.ui.strategy_preset

import javafx.beans.property.ReadOnlyObjectProperty
import javafx.beans.property.SimpleObjectProperty
import lin.repository.card_group.PresetSummary
import lin.repository.card_group.StrategyPresetService
import lin.repository.card_group.SurplusOverride
import lin.repository.card_group.TimingOverride
import lin.ui.card_group.ActiveManagerHolder
import lin.ui.service.PresetCatalogLoader

/**
 * 策略预设工作台状态管理与业务中枢（T-TG-016 / T-TG-017）。
 *
 * 单向数据流：UI 事件 -> Store 方法 -> 更新不可变 State -> 触发 stateProperty -> UI 响应。
 */
class StrategyPresetStore(
    private val service: StrategyPresetService,
    private val activeManagerHolder: ActiveManagerHolder,
    private val catalogLoader: PresetCatalogLoader
) {
    private val stateProperty = SimpleObjectProperty(StrategyPresetState())
    val state: StrategyPresetState get() = stateProperty.value

    fun stateProperty(): ReadOnlyObjectProperty<StrategyPresetState> = stateProperty

    /**
     * 加载初始/最新数据。
     *
     * ⚠️ Q4 裁定：仅在进入工作台（onActive）或显式刷新时读取一次当前卡组 ID，
     * 不对 activeManagerProperty 进行持续监听（预设为跨卡组复用资产）。
     */
    fun loadInitialData() {
        val activeDeckId = activeManagerHolder.activeManagerId
        // T-TG-024: 预设域目录单点装配（列表 + 候选树 + 时序规则 + 用途全集）
        val catalog = catalogLoader.load()
        val all = catalog.presets
        val prevSearch = state.searchText
        val filtered = filterPresets(all, prevSearch)

        // 维持原先的选中预设；若原预设已被删除或原本无选中，则默认选中过滤后列表的首项
        val targetId = when {
            state.isCreating -> null
            state.selectedPresetId != null && all.any { it.preset.id == state.selectedPresetId } -> state.selectedPresetId
            filtered.isNotEmpty() -> filtered.first().preset.id
            else -> null
        }

        val detail = targetId?.let { service.findDetail(it) }

        stateProperty.set(
            state.copy(
                allPresets = all,
                filteredPresets = filtered,
                selectedPresetId = targetId,
                selectedDetail = detail,
                activeDeckId = activeDeckId,
                isCreating = state.isCreating && targetId == null,
                candidateTrees = catalog.candidateTrees,
                timingRules = catalog.timingRules,
                purposeUniverse = catalog.purposeUniverse,
                tagDisplayNames = catalog.tagDisplayNames,
                candidateAuraBoosts = catalog.candidateAuraBoosts
            )
        )
    }

    /** 更新搜索关键词并刷新过滤列表 */
    fun updateSearchText(text: String) {
        val clean = text.trim()
        val filtered = filterPresets(state.allPresets, clean)
        val currentSelectedStillValid = filtered.any { it.preset.id == state.selectedPresetId }
        val targetId = if (currentSelectedStillValid) state.selectedPresetId else filtered.firstOrNull()?.preset?.id
        val detail = targetId?.let { service.findDetail(it) }

        stateProperty.set(
            state.copy(
                searchText = clean,
                filteredPresets = filtered,
                selectedPresetId = targetId,
                selectedDetail = detail,
                isCreating = false
            )
        )
    }

    /** 选中某条预设 */
    fun selectPreset(presetId: String?) {
        if (presetId == null) {
            stateProperty.set(state.copy(selectedPresetId = null, selectedDetail = null, isCreating = false))
            return
        }
        val detail = service.findDetail(presetId)
        stateProperty.set(
            state.copy(
                selectedPresetId = presetId,
                selectedDetail = detail,
                isCreating = false
            )
        )
    }

    /** 进入新建模式 */
    fun enterCreatingMode() {
        stateProperty.set(
            state.copy(
                selectedPresetId = null,
                selectedDetail = null,
                isCreating = true
            )
        )
    }

    /**
     * 保存预设元数据（新建或重命名/修改描述）。
     *
     * ⚠️ treeSelections 与 timings 传 null，表示保留已有维度项不变（只更新名称与描述）。
     *
     * @return 错误信息；null 表示保存成功
     */
    fun savePresetMetadata(presetId: String?, name: String, description: String?): String? =
        savePreset(presetId, name, description, treeSelections = null, timings = null)

    /**
     * 保存完整预设（含元数据、树白名单、时序声明与惜售声明）。
     *
     * ⚠️ 整体替换语义（StrategyPresetService 契约）：
     * 各维度传 null 表示不修改；非 null 时按当前传入的内容整体替换。
     *
     * @param surplus T-TG-029 惜售维度（独立于 timings）
     * @return 错误信息；null 表示保存成功
     */
    fun savePreset(
        presetId: String?,
        name: String,
        description: String?,
        treeSelections: Map<String, Collection<String>>?,
        timings: Map<String, TimingOverride>?,
        surplus: Map<String, SurplusOverride>? = null,
        auraSelection: Set<String>? = null
    ): String? {
        val cleanName = name.trim()
        if (cleanName.isBlank()) return "预设名称不能为空"
        if (cleanName.length > 60) return "预设名称过长（最多 60 字符）"

        val targetId = presetId?.trim()?.takeIf { it.isNotBlank() }
        val result = service.savePreset(
            presetId = targetId,
            name = cleanName,
            description = description?.trim()?.takeIf { it.isNotBlank() },
            treeSelections = treeSelections,
            timings = timings,
            surplus = surplus,
            auraSelection = auraSelection
        ) ?: return "更新失败：预设不存在 ($targetId)"

        // 保存成功后刷新数据并选中该预设
        loadInitialData()
        selectPreset(result.presetId)
        return null
    }

    /**
     * 从现有预设**派生**新预设（fork，D-TG-017）—— 转发到域服务，UI 侧只做校验与选中。
     *
     * ⚠️ fork = 只复制内容、不建立关系：新预设与源预设此后各自独立演化（改源预设不传导到派生预设）。
     * ⚠️ 源预设漏声明的用途会被一并继承（未声明 = 该用途树全禁）。
     *
     * @return 错误信息；null 表示派生成功（成功后刷新列表并打开新预设详情）
     */
    fun clonePreset(sourceId: String, name: String, description: String? = null): String? {
        val cleanName = name.trim()
        if (cleanName.isBlank()) return "预设名称不能为空"
        if (cleanName.length > 60) return "预设名称过长（最多 60 字符）"

        val source = sourceId.trim().takeIf { it.isNotBlank() } ?: return "派生失败：未指定源预设"
        val result = service.clonePreset(
            sourceId = source,
            name = cleanName,
            description = description?.trim()?.takeIf { it.isNotBlank() }
        ) ?: return "派生失败：源预设不存在 ($source)"

        // 派生成功后刷新数据并选中/打开新预设详情
        loadInitialData()
        selectPreset(result.presetId)
        return null
    }

    /**
     * 删除预设（UI 层面在调用前已完成是否有引用的阻断拦截）。
     *
     * ⚠️ D-TG-016 裁定：UI 删除是用户自主意图操作，直接物理删除不落快照（不可恢复）。
     */
    fun deletePreset(presetId: String): Boolean {
        val deleted = service.deletePreset(presetId) != null
        if (deleted) {
            stateProperty.set(state.copy(selectedPresetId = null, selectedDetail = null))
            loadInitialData()
        }
        return deleted
    }

    private fun filterPresets(presets: List<PresetSummary>, search: String): List<PresetSummary> {
        if (search.isBlank()) return presets
        return presets.filter {
            it.preset.name.contains(search, ignoreCase = true) ||
                    it.preset.id.contains(search, ignoreCase = true) ||
                    (it.preset.description?.contains(search, ignoreCase = true) == true)
        }
    }
}
