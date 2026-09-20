package lin.ui.components.action

import javafx.beans.value.ObservableValue
import javafx.scene.control.Button
import javafx.scene.layout.VBox
import lin.ui.components.state.EditorState

/**
 * 编辑器动作栏：消费 [EditorAction] 值列表，按 [EditorState] 多态驱动按钮渲染与禁用/显隐（零 if）。
 *
 * 架构定位 = 展现与解析层（与 [ResourcePickerBar] 同构，只是消费的语义轴是编辑器状态而非选中项）：
 * 1. 接收 [EditorAction] 值列表与编辑器状态源；
 * 2. 渲染按钮（label / variant 样式），点击由 [EditorAction.execute] 多态派发；
 * 3. 监听状态变化，由 [EditorAction.isEnabled] / [EditorAction.isVisible] 多态重算禁用与显隐，杜绝 if-else。
 */
class EditorActionBar(
    stateProperty: ObservableValue<EditorState<*>>,
    actions: List<EditorAction>,
    spacing: Double = 8.0
) : VBox(spacing) {

    private val buttons: List<Pair<EditorAction, Button>> = actions.map { action ->
        action to Button(action.label).apply {
            style = "${action.variant.cssStyle} -fx-font-size: 14px; -fx-font-weight: bold; -fx-padding: 10px;"
            maxWidth = Double.MAX_VALUE
            setOnAction { action.execute() }
        }
    }

    init {
        buttons.forEach { (_, btn) -> children.add(btn) }
        stateProperty.addListener { _, _, _ -> updateState() }
        updateState()
    }

    private fun updateState() {
        val state = stateProperty.value
        buttons.forEach { (action, btn) ->
            btn.isDisable = !action.isEnabled(state)
            btn.isVisible = action.isVisible(state)
            btn.isManaged = action.isVisible(state)
        }
    }
}
