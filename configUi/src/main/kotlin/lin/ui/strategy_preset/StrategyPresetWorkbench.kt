package lin.ui.strategy_preset

import javafx.scene.control.SplitPane
import lin.repository.card_group.StrategyPresetService
import lin.ui.ActiveAware
import lin.ui.card_group.ActiveManagerHolder
import lin.ui.service.PresetCatalogLoader
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * 策略预设独立工作台（T-TG-016 / T-TG-017）。
 *
 * 采用成熟左右分栏架构，通过 [StrategyPresetStore] 驱动单向数据流与 UI 状态同步。
 * 遵循 AuraBoostWorkbench 范式：ActiveAware 懒加载 + isUpdatingFromState 防回环。
 */
class StrategyPresetWorkbench : SplitPane(), KoinComponent, ActiveAware {

    private val service: StrategyPresetService by inject()
    private val activeManagerHolder: ActiveManagerHolder by inject()
    private val catalogLoader: PresetCatalogLoader by inject()

    private val store = StrategyPresetStore(service, activeManagerHolder, catalogLoader)

    private val listPane = StrategyPresetListPane()
    private val detailPane = StrategyPresetDetailPane(service)

    private var isUpdatingFromState = false

    init {
        items.addAll(listPane, detailPane)
        setDividerPositions(0.42)

        setupEventBindings()
        setupStateObserver()
    }

    private fun setupEventBindings() {
        // 左侧列表交互绑定
        listPane.onSelectPreset = { presetId ->
            if (!isUpdatingFromState) {
                store.selectPreset(presetId)
            }
        }

        listPane.onNewClicked = {
            if (!isUpdatingFromState) {
                store.enterCreatingMode()
            }
        }

        listPane.onSearchChanged = { text ->
            if (!isUpdatingFromState) {
                store.updateSearchText(text)
            }
        }

        listPane.onRefreshClicked = {
            store.loadInitialData()
        }

        // 右侧详情交互绑定（完整预设保存：元数据 + 树白名单 + 时序声明 + 惜售声明）
        detailPane.onSavePreset = { presetId, name, description, treeSelections, timings, surplus, auraSelection ->
            val error = store.savePreset(presetId, name, description, treeSelections, timings, surplus, auraSelection)
            if (error != null) {
                println("[StrategyPresetWorkbench] 保存预设失败: $error")
            }
        }

        detailPane.onDeletePreset = { presetId ->
            store.deletePreset(presetId)
        }

        // 另存为新预设（fork 派生，D-TG-017）；成功后 Store 已刷新列表并选中新预设
        detailPane.onClonePreset = { sourceId, name, description ->
            val error = store.clonePreset(sourceId, name, description)
            if (error != null) {
                detailPane.showError(error)
            }
        }
    }

    private fun setupStateObserver() {
        store.stateProperty().addListener { _, _, newState ->
            isUpdatingFromState = true
            try {
                listPane.updateState(newState)
                detailPane.updateState(newState)
            } finally {
                isUpdatingFromState = false
            }
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

