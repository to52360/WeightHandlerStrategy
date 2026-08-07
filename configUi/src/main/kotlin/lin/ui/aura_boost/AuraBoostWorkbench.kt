package lin.ui.aura_boost

import javafx.beans.value.ChangeListener
import javafx.scene.control.SplitPane
import lin.repository.aura_boost.AuraBoostConfigService
import lin.repository.card_group.CardGroupService
import lin.repository.card_group.CardManagerEntity
import lin.repository.condition_tree.ConditionTreeConfigRepository
import lin.ui.ActiveAware
import lin.ui.card_group.ActiveManagerHolder
import lin.ui.card_group.behavior.ConditionTreeOption
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class AuraBoostWorkbench : SplitPane(), KoinComponent, ActiveAware {

    private var managerListener: ChangeListener<CardManagerEntity?>? = null

    private val service: AuraBoostConfigService by inject()
    private val cardGroupService: CardGroupService by inject()
    private val conditionTreeRepository: ConditionTreeConfigRepository by inject()
    private val activeManagerHolder: ActiveManagerHolder by inject()

    private val store = AuraBoostStore(service, cardGroupService, conditionTreeRepository, activeManagerHolder)

    private val listPanel = AuraBoostListPanel()
    private val editorPanel = AuraBoostEditorPanel()

    private var isUpdatingFromState = false
    private var isCreatingMode = false

    init {
        // 解耦拆分左右组件并置入 SplitPane
        items.addAll(listPanel, editorPanel)
        setDividerPositions(0.52)

        setupEventBindings()
        setupStateObserver()
    }

    private fun setupEventBindings() {
        // 左侧列表交互绑定
        listPanel.onSelectEntity = { selection ->
            if (!isUpdatingFromState) {
                isCreatingMode = false
                store.selectEntity(selection)
            }
        }

        listPanel.onFilterChanged = { searchText, managerId ->
            store.updateFilters(searchText, managerId)
        }

        listPanel.onNewClicked = {
            enterCreatingMode()
        }

        // 右侧编辑器交互绑定
        editorPanel.onSave = { input ->
            store.saveEntity(input)
            isCreatingMode = false
        }

        editorPanel.onDelete = { id ->
            store.deleteEntity(id)
            isCreatingMode = false
        }

        editorPanel.onRefreshTreesRequested = {
            store.loadInitialData()
        }
    }

    private fun setupStateObserver() {
        store.stateProperty().addListener { _, oldState, newState ->
            isUpdatingFromState = true
            try {
                // 转换条件树选项列表
                val treeOptions = newState.conditionTreeMap.toConditionTreeOptions()

                // 1. 同步左侧与右侧的卡组方案选项列表
                if (oldState.allManagers != newState.allManagers || oldState.filterManagerId != newState.filterManagerId) {
                    listPanel.syncManagers(newState.allManagers, newState.filterManagerId)
                    editorPanel.syncManagers(newState.allManagers)
                }

                // 2. 同步左侧表格实体数据
                listPanel.updateEntities(newState.filteredEntities, newState.conditionTreeMap, newState.selectedEntity)

                // 3. 驱动右侧编辑器渲染
                val selected = newState.selectedEntity
                if (selected == null) {
                    if (!isCreatingMode) {
                        // 空状态也同步条件树下拉选项（否则 items 从未填充，展开下拉为空）
                        editorPanel.syncConditionTrees(treeOptions)
                        editorPanel.clearEditor()
                    }
                } else {
                    isCreatingMode = false
                    editorPanel.loadEntity(selected, newState.allManagers, treeOptions)
                }
            } finally {
                isUpdatingFromState = false
            }
        }
    }

    override fun onActive() {
        store.loadInitialData()

        if (managerListener == null) {
            val listener = ChangeListener<CardManagerEntity?> { _, _, _ ->
                store.loadInitialData()
            }
            activeManagerHolder.activeManagerProperty.addListener(listener)
            managerListener = listener
        }
    }

    private fun enterCreatingMode() {
        isCreatingMode = true
        listPanel.clearSelection()
        store.selectEntity(null)

        val defaultManagerId = activeManagerHolder.activeManagerId
        editorPanel.enterCreatingMode(
            store.state.allManagers,
            defaultManagerId,
            store.state.conditionTreeMap.toConditionTreeOptions()
        )
    }

    private fun Map<String, String>.toConditionTreeOptions(): List<ConditionTreeOption> =
        map { (id, name) -> ConditionTreeOption(id, name) }
}
