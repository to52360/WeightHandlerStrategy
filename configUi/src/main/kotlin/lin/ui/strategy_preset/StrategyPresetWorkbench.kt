package lin.ui.strategy_preset

import javafx.scene.control.SplitPane
import lin.repository.card_group.StrategyPresetService
import lin.ui.ActiveAware
import lin.ui.card_group.ActiveManagerHolder
import lin.ui.components.state.EditorStateTransition
import lin.ui.service.PresetCatalogLoader
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * 策略预设独立工作台（T-TG-016 / T-TG-017）。
 *
 * 采用成熟左右分栏架构，通过 [StrategyPresetStore] 驱动单向数据流与 UI 状态同步。
 * 遵循 AuraBoostWorkbench 范式：ActiveAware 懒加载 + **幂等回环防护**（不再用脏标志位抑制）。
 */
class StrategyPresetWorkbench : SplitPane(), KoinComponent, ActiveAware {

    private val service: StrategyPresetService by inject()
    private val activeManagerHolder: ActiveManagerHolder by inject()
    private val catalogLoader: PresetCatalogLoader by inject()

    private val store = StrategyPresetStore(service, activeManagerHolder, catalogLoader)

    private val listPane = StrategyPresetListPane()

    // 右侧详情面板：保存/删除/派生回调构造注入（面板自用依赖，杜绝 late-set 可空回调）
    private val detailPane = StrategyPresetDetailPane(
        presetStateProvider = { store.state },
        onSavePreset = { input ->
            val error = store.savePreset(input)
            if (error != null) {
                println("[StrategyPresetWorkbench] 保存预设失败: $error")
            }
        },
        onDeletePreset = { presetId -> handleDeletePreset(presetId) },
        // 另存为新预设（fork 派生，D-TG-017）；成功后 Store 已刷新列表并选中新预设
        onClonePreset = { sourceId, name, description -> handleClonePreset(sourceId, name, description) }
    )

    /**
     * 编辑器状态过渡器：状态变化才推送（不打断用户草稿），用户主动「新建」时 `force` 重置草稿。
     *
     * 机制单点在 `EditorStateTransition`（`D-DC-005`），工作台不自行比较快照。
     */
    private val editorTransition = EditorStateTransition(
        stateProvider = { store.editorState() },
        onTransition = { editorState -> detailPane.render(store.state, editorState) }
    )

    init {
        items.addAll(listPane, detailPane)
        setDividerPositions(0.55)

        setupEventBindings()
        setupStateObserver()
    }

    private fun setupEventBindings() {
        // 左侧列表交互绑定（幂等回环防护：状态层已是该值时不再回设，替代原 isUpdatingFromState 抑制）
        listPane.onSelectPreset = { presetId ->
            if (presetId != store.state.selectedPresetId) {
                store.selectPreset(presetId)
            }
        }

        listPane.onNewClicked = {
            store.enterCreatingMode()
            // 用户主动点击「新建」= 显式重置草稿（sync 会因相位未变而幂等跳过，故走意图通道）
            editorTransition.force()
        }

        listPane.onSearchChanged = { text ->
            if (text.trim() != store.state.searchText) {
                store.updateSearchText(text)
            }
        }

        listPane.onRefreshClicked = {
            store.loadInitialData()
        }
    }

    private fun setupStateObserver() {
        store.stateProperty().addListener { _, _, newState ->
            listPane.updateState(newState)
            // 详情面板：状态机 = store 单一事实源，仅相位/实体变化时过渡渲染（不打断草稿）
            editorTransition.sync()
            // 动作可用性条件可能依赖相位以外的数据（如「是否被引用」）⇒ 每次发射都重算按钮
            detailPane.refreshActions()
        }
    }

    /** 另存为新预设：成功由 Store 刷新列表并选中新预设，失败回显错误原因 */
    private fun handleClonePreset(sourceId: String, name: String, description: String?) {
        val error = store.clonePreset(sourceId, name, description)
        if (error != null) {
            detailPane.showError(error)
        }
    }

    /** 删除预设：引用守卫在 Store（单点），此处只负责回显失败原因 */
    private fun handleDeletePreset(presetId: String) {
        val error = store.deletePreset(presetId)
        if (error != null) {
            detailPane.showError(error)
        }
    }

    /**
     * 外部指定选中特定预设（如从卡组工作台点击跳转）。
     */
    fun selectPresetById(presetId: String) {
        store.selectPreset(presetId)
    }

    override fun onActive() {
        // 进入工作台时拉取最新数据，并读取当前卡组 ID 用于状态标注
        store.loadInitialData()
    }
}

