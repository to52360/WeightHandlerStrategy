package lin.ui.card_group

import javafx.scene.control.SplitPane
import lin.repository.card_group.CardGroupCascadeDeleteService
import lin.repository.card_group.CardGroupService
import lin.repository.card_group.StrategyPresetService
import lin.ui.ActiveAware
import lin.ui.service.PresetCatalogLoader
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * 卡组管理工作台
 *
 * 行为与数据流：
 * User -> UI Component -> Store.dispatch(Action) / Store Method -> Action(State)->State -> Store.stateProperty -> UI Component (Diff 更新)
 */
class CardGroupWorkbench : SplitPane(), KoinComponent, ActiveAware {

    private val service: CardGroupService by inject()
    private val presetService: StrategyPresetService by inject()
    private val catalogLoader: PresetCatalogLoader by inject()
    private val cascadeDeleteService: CardGroupCascadeDeleteService by inject()

    private val store = WorkbenchStore(service, presetService, catalogLoader, cascadeDeleteService)

    init {
        // 1. 左侧：Manager 列表区
        val leftPanel = ManagerListPane(store)

        // 2. 右侧：详情编辑区
        val rightPanel = BindingEditorPane(store)

        items.addAll(leftPanel, rightPanel)
        setDividerPositions(0.25)

        // 初始化加载数据
        store.loadInitialData()
    }

    override fun onActive() {
        store.loadInitialData()
    }

    /**
     * 跨模块跳转定位：根据 managerId 选中对应的卡组方案
     */
    fun selectManagerById(managerId: String) {
        val target = store.state.managers.find { it.entity?.id == managerId }
        if (target != null) {
            store.selectManager(target)
        }
    }
}

