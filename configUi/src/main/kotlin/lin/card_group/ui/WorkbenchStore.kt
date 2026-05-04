package lin.card_group.ui

import javafx.beans.property.SimpleObjectProperty
import lin.card_group.domain.CardManagerEntity
import lin.card_group.service.BindingDraft
import lin.card_group.service.CardGroupService
import lin.dao.CardGroupJsonParser
import lin.rule.tree.CardGroupBinding
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
            val bindings = service.loadBindingViews(item.entity!!.id).map {
                BindingDraft(it.binding, it.sourceFile)
            }
            dispatch(WorkbenchActions.selectManager(item, bindings))
        }
    }

    fun createNewManager() {
        val draftEntity = CardManagerEntity(
            id = UUID.randomUUID().toString().substring(0, 8),
            name = "新分组方案",
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
            enabled = state.managerEnabled,
            drafts = state.currentBindings,
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
        val draft = state.currentBindings[index]

        // 副作用：从文件加载全量卡池
        val cardPool = CardGroupJsonParser.loadByFileName(draft.sourceFile)?.cards ?: emptyList()
        dispatch(WorkbenchActions.selectBinding(index, cardPool))
    }

    fun addBinding(sourceFile: String) {
        val draft = BindingDraft(
            binding = CardGroupBinding(
                id = UUID.randomUUID().toString().substring(0, 8),
                mangerId = state.selectedManagerItem?.entity?.id ?: "",
                name = sourceFile,
                cardIds = emptyList()
            ),
            sourceFile = sourceFile
        )
        dispatch(WorkbenchActions.addBinding(draft))
    }
}
