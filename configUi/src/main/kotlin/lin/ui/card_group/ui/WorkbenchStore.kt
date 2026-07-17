package lin.ui.card_group.ui

import javafx.beans.property.SimpleObjectProperty

import lin.dao.CardGroupJsonParser
import lin.domain.MatchState
import lin.rule.tree.CardGroupBinding
import lin.ui.card_group.db.CardGroupService
import lin.ui.card_group.db.CardManagerEntity
import java.util.*

class WorkbenchStore(private val service: CardGroupService) {

    val stateProperty = SimpleObjectProperty(WorkbenchState())
    var state: WorkbenchState
        get() = stateProperty.get()
        private set(value) = stateProperty.set(value)

    fun dispatch(action: Action) {
        state = action(state)
    }

    // ==========================================
    // 包含副作用的复杂操作 (类似 Redux Thunk / MVI Intent)
    // ==========================================

    fun loadInitialData() {
        val managers = service.loadAllManagers().map { CardManagerItem(it) }
        dispatch(WorkbenchActions.setManagers(managers))
    }

    fun selectManager(item: CardManagerItem?) {
        if (item == null) {
            dispatch(WorkbenchActions.selectManager(null, emptyList()))
            return
        }

        if (item.isDraft) {
            // 如果是草稿，不用查 DB
            dispatch(WorkbenchActions.selectManager(item, emptyList()))
        } else {
            // 查 DB 获取 Bindings
            val bindings = service.loadBindings(item.entity!!.id)
            dispatch(WorkbenchActions.selectManager(item, bindings))
        }
    }

    fun createNewManager(sourceFile: String, name: String) {
        val draftEntity = CardManagerEntity(
            id = UUID.randomUUID().toString().substring(0, 8),
            name = name,
            sourceFile = sourceFile,
            enabled = true
        )
        val newItem = CardManagerItem(draftEntity, isDraft = true)
        val newManagers = listOf(newItem) + state.managers
        dispatch(WorkbenchActions.setManagers(newManagers))
        selectManager(newItem)
    }

    fun saveCurrentManager() {
        val currentItem = state.selectedManagerItem ?: return

        // 收集数据并保存
        val id = service.saveManager(
            name = state.managerName,
            sourceFile = state.managerSourceFile,
            enabled = state.managerEnabled,
            bindings = state.currentBindings,
            existingId = if (currentItem.isDraft) null else currentItem.entity?.id
        )

        // 保存后重新加载整体列表
        loadInitialData()

        // 尝试重新选中刚刚保存的项
        val newManager = state.managers.find { it.entity?.id == id }
        selectManager(newManager)
    }

    fun deleteCurrentManager() {
        val currentItem = state.selectedManagerItem ?: return
        if (!currentItem.isDraft && currentItem.entity != null) {
            service.deleteManager(currentItem.entity.id)
        }
        loadInitialData()
        selectManager(null)
    }

    fun selectBinding(index: Int?) {
        if (index == null || index < 0 || index >= state.currentBindings.size) {
            dispatch(WorkbenchActions.selectBinding(null, emptyList()))
            return
        }

        // 副作用：从文件加载全量卡池 (使用 Manager 级别的 sourceFile)
        val cardPool = CardGroupJsonParser.loadByFileName(state.managerSourceFile)?.cards ?: emptyList()
        dispatch(WorkbenchActions.selectBinding(index, cardPool))
    }

    fun addBinding() {
        val nextNum = state.currentBindings.size + 1
        val binding = CardGroupBinding(
            id = UUID.randomUUID().toString().substring(0, 8),
            managerId = state.selectedManagerItem?.entity?.id ?: "",
            name = "分组 $nextNum",
            cardIds = emptyList()
        )
        dispatch(WorkbenchActions.addBinding(binding))
    }

    fun updateBindingName(newName: String) {
        val idx = state.selectedBindingIndex ?: return
        dispatch(WorkbenchActions.updateBindingName(idx, newName))
    }

    fun updateBindingStageOverride(stage: String?) {
        val idx = state.selectedBindingIndex ?: return
        dispatch(WorkbenchActions.updateBindingStageOverride(idx, stage))
    }

    fun updateBindingReplanAfterUse(replan: Boolean?) {
        val idx = state.selectedBindingIndex ?: return
        dispatch(WorkbenchActions.updateBindingReplanAfterUse(idx, replan))
    }

    fun updateBindingOrderWeight(weight: Double) {
        val idx = state.selectedBindingIndex ?: return
        dispatch(WorkbenchActions.updateBindingOrderWeight(idx, weight))
    }

    fun updateBindingUseAction(actionId: String, enabled: Boolean) {
        val idx = state.selectedBindingIndex ?: return
        dispatch(WorkbenchActions.updateBindingUseAction(idx, actionId, enabled))
    }

    fun updateBindingStatDimensions(dimensions: List<MatchState.StatDimension>) {
        val idx = state.selectedBindingIndex ?: return
        dispatch(WorkbenchActions.updateBindingStatDimensions(idx, dimensions))
    }
}
