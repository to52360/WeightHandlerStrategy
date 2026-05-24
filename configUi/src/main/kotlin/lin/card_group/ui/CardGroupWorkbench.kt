package lin.card_group.ui

import javafx.scene.control.SplitPane
import lin.card_group.db.CardGroupService
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * 卡牌分组管理工作台
 * 采用左右分栏布局，内部通过 WorkbenchStore 共享状态和处理业务。
 * 行为与数据流：
 * User -> UI Component -> Store.dispatch(Action) / Store Method -> Action(State)->State -> Store.stateProperty -> UI Component (Diff 更新)
 */
class CardGroupWorkbench : SplitPane(), KoinComponent {

    private val service: CardGroupService by inject()
    private val store = WorkbenchStore(service)

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
}

