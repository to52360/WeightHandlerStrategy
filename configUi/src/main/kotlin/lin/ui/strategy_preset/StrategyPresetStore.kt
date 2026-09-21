package lin.ui.strategy_preset

import javafx.beans.property.ReadOnlyObjectProperty
import javafx.beans.property.SimpleObjectProperty
import lin.repository.card_group.PresetDetail
import lin.repository.card_group.PresetSaveInput
import lin.repository.card_group.PresetSummary
import lin.repository.card_group.StrategyPresetService
import lin.ui.card_group.ActiveManagerHolder
import lin.ui.components.state.EditorState
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
     * 仅保存预设元数据（新建 / 重命名 / 改描述），维度保持不变。
     *
     * @return 错误信息；null 表示保存成功
     */
    fun savePresetMetadata(presetId: String?, name: String, description: String?): String? =
        savePreset(PresetSaveInput(presetId, name, description))

    /**
     * 保存预设（元数据 + 可选的策略声明四维度）。
     *
     * ⚠️ 整体替换语义（[StrategyPresetService.savePreset] 契约）：
     * [PresetSaveInput.declaration] 传 null 表示不修改维度；非 null 时按传入内容整体替换。
     *
     * @return 错误信息；null 表示保存成功
     */
    fun savePreset(input: PresetSaveInput): String? {
        val cleanName = input.name.trim()
        if (cleanName.isBlank()) return "预设名称不能为空"
        if (cleanName.length > 60) return "预设名称过长（最多 60 字符）"

        val targetId = input.presetId?.trim()?.takeIf { it.isNotBlank() }
        val result = service.savePreset(
            PresetSaveInput(
                presetId = targetId,
                name = cleanName,
                description = input.description?.trim()?.takeIf { it.isNotBlank() },
                declaration = input.declaration
            )
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
     * 删除预设。
     *
     * ⚠️ **引用守卫在此处（权威判据）**：仍被卡组引用时拒绝并返回原因文案。
     * 原实现把这道守卫留在 UI 面板，是唯一防线——一旦某入口遗漏即静默删除被引用预设，
     * 故下沉到 Store 单点（与 `savePreset` / `clonePreset` 同风格：返回错误文案，null = 成功）。
     *
     * ⚠️ D-TG-016 裁定：UI 删除是用户自主意图操作，直接物理删除不落快照（不可恢复）。
     *
     * @return 错误信息；null 表示删除成功
     */
    fun deletePreset(presetId: String): String? {
        val references = service.findReferences(presetId)
        if (references.isNotEmpty()) {
            val refInfo = references.joinToString("\n") { "• ${it.managerName} (id: ${it.managerId})" }
            return "该策略预设正被以下 ${references.size} 个卡组引用，已被系统阻断：\n\n$refInfo\n\n" +
                    "请先前往「卡组分组管理」解除这些卡组的预设引用后重试。"
        }

        service.deletePreset(presetId)
        stateProperty.set(state.copy(selectedPresetId = null, selectedDetail = null, isCreating = false))
        loadInitialData()
        return null
    }

    /**
     * 编辑器状态（**单一事实源**）：标题 / 徽标 / 动作可用性全部由它驱动。
     */
    fun editorState(): EditorState<PresetDetail> {
        val current = state
        val detail = current.selectedDetail
        return when {
            current.isCreating -> EditorState.Creating("新建策略预设")
            detail != null -> EditorState.Editing(
                entity = detail,
                title = "编辑策略预设",
                badge = detail.preset.id
            )
            else -> EditorState.Empty("请在左侧选择或新建预设")
        }
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
