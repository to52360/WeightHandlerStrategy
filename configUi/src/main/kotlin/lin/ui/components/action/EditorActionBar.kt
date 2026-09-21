package lin.ui.components.action

import javafx.beans.value.ObservableValue
import javafx.scene.Node
import javafx.scene.layout.StackPane
import lin.ui.components.state.EditorState

/**
 * 编辑器动作栏：消费 [EditorAction] 值列表，按 [EditorState] 相位驱动按钮渲染与禁用/显隐（零 if）。
 *
 * 架构定位 = **解析层**（与 [ResourcePickerBar] 同构，只是消费的语义轴是编辑器状态而非选中项）：
 * 1. 接收 [EditorAction] 值列表、编辑器状态源与**展现器**；
 * 2. 请展现器造节点并排列（本层不含任何布局分支），点击直接调用 [EditorAction.handle]；
 * 3. 监听状态变化，由 [EditorAction.isEnabled] / [EditorAction.isVisible] 按相位集合重算禁用与显隐。
 *
 * 「造节点」与「排列」都在 [EditorActionPresenter]（展现层）——换布局 = 换展现器，业务声明侧零改动。
 */
class EditorActionBar(
    // 「只读消费」→ 声明 out 投影，任意 EditorState<T> 属性可直传（ObservableValue 不变型）
    private val stateProperty: ObservableValue<out EditorState<*>>,
    actions: List<EditorAction>,
    presenter: EditorActionPresenter = StackedActionPresenter()
) : StackPane() {

    private val buttons: List<Pair<EditorAction, Node>> = actions.map { action ->
        action to presenter.node(action) { action.handle() }
    }

    init {
        children.add(presenter.arrange(buttons.map { it.second }))
        stateProperty.addListener { _, _, _ -> updateState() }
        updateState()
    }

    /**
     * 主动重算一次各按钮状态（幂等）。
     *
     * 必要场景：条件里含 [ActionCondition.Guard] 时，其依据的数据**不在 [EditorState] 里**
     * （如「该预设是否被卡组引用」在 Store 状态中）⇒ 相位未变而依据变化时，本层收不到通知。
     * 由工作台在每次状态发射点调用本方法，使重算时机从「仅相位变化」放宽到「任意状态发射」。
     */
    fun refresh() = updateState()

    private fun updateState() {
        val state = stateProperty.value
        buttons.forEach { (action, node) ->
            node.isDisable = !action.isEnabled(state)
            node.isVisible = action.isVisible(state)
            node.isManaged = action.isVisible(state)
        }
    }
}
