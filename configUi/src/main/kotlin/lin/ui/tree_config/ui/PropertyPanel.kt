package lin.ui.tree_config.ui

import javafx.geometry.Insets
import javafx.scene.control.Label
import javafx.scene.layout.VBox
import lin.ui.components.PropertyEditorStrategy

/**
 * 右侧属性面板：通用容器，根据选中节点类型委托给对应的 PropertyEditorStrategy 渲染表单。
 */
class PropertyPanel<L>(private val strategy: PropertyEditorStrategy<L>) : VBox(8.0) {

    var onChanged: (() -> Unit)? = null

    init {
        padding = Insets(10.0)
        children.add(Label("节点属性").apply {
            style = "-fx-font-weight: bold; -fx-padding: 0 0 5 0;"
        })
        showPlaceholder()
    }

    fun showPlaceholder() {
        clearContent()
        children.add(Label("请在中间树中选择一个节点...").apply {
            style = "-fx-text-fill: #888;"
        })
    }

    fun showNode(wrapper: LogicNodeWrapper<L>) {
        clearContent()
        if (strategy.canEdit(wrapper.type)) {
            strategy.render(this, wrapper) { onChanged?.invoke() }
        } else {
            children.add(Label("类型: ${wrapper.type.name}").apply {
                style = "-fx-font-size: 14px;"
            })
            children.add(Label("结构节点，无需配置额外属性。").apply {
                style = "-fx-text-fill: #888;"
            })
        }
    }

    private fun clearContent() {
        // 保留第一个 title Label
        if (children.size > 1) {
            children.subList(1, children.size).clear()
        }
    }
}
