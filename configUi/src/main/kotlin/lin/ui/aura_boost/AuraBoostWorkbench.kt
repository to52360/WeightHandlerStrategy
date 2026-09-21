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
import lin.ui.components.state.EditorStateTransition
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

    // 右侧编辑器：保存/删除/刷新回调构造注入（面板自用依赖，杜绝 late-set 可空回调）
    private val editorPanel = AuraBoostEditorPanel(
        onSave = { input -> store.saveEntity(input) },
        onDelete = { id -> store.deleteEntity(id) },
        onRefreshTreesRequested = { store.loadInitialData() }
    )

    /**
     * 编辑器状态过渡器：状态变化才推送（不打断用户草稿），用户主动「新建」时 `force` 重置草稿。
     *
     * 机制单点在 [EditorStateTransition]，工作台不自行比较快照。
     */
    private val editorTransition = EditorStateTransition(
        stateProvider = { store.editorState() },
        onTransition = { state ->
            editorPanel.render(
                state = state,
                managers = store.state.allManagers,
                treeOptions = store.state.conditionTreeMap.toConditionTreeOptions(),
                defaultManagerId = activeManagerHolder.activeManagerId
            )
        }
    )

    init {
        // 解耦拆分左右组件并置入 SplitPane
        items.addAll(listPanel, editorPanel)
        setDividerPositions(0.52)

        setupEventBindings()
        setupStateObserver()
    }

    private fun setupEventBindings() {
        // 左侧列表交互绑定（幂等回环防护：状态层已是该选中项时不再回设，替代原 isUpdatingFromState 抑制）
        listPanel.onSelectEntity = { selection ->
            if (selection != store.state.selectedEntity) {
                store.selectEntity(selection)
            }
        }

        listPanel.onFilterChanged = { searchText, managerId ->
            store.updateFilters(searchText, managerId)
        }

        listPanel.onNewClicked = {
            store.enterCreatingMode()
            // 用户主动点击「新建」= 显式重置草稿（sync 会因相位未变而幂等跳过，故走意图通道）
            editorTransition.force()
        }
    }

    private fun setupStateObserver() {
        store.stateProperty().addListener { _, oldState, newState ->
            // 转换条件树选项列表
            val treeOptions = newState.conditionTreeMap.toConditionTreeOptions()

            // 1. 同步左侧与右侧的卡组方案选项列表
            if (oldState.allManagers != newState.allManagers || oldState.filterManagerId != newState.filterManagerId) {
                listPanel.syncManagers(newState.allManagers, newState.filterManagerId)
                editorPanel.syncManagers(newState.allManagers)
            }

            // 2. 同步左侧表格实体数据
            listPanel.updateEntities(newState.filteredEntities, newState.conditionTreeMap, newState.selectedEntity)

            // 3. 条件树候选下拉（幂等：自持选中项，新建/刷新树后立即出现在下拉里）
            editorPanel.syncConditionTrees(treeOptions)

            // 4. 驱动右侧编辑器：状态机 = store 单一事实源，仅相位/实体变化时过渡渲染（不打断草稿）
            editorTransition.sync()
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

    private fun Map<String, String>.toConditionTreeOptions(): List<ConditionTreeOption> =
        map { (id, name) -> ConditionTreeOption(id, name) }
}
