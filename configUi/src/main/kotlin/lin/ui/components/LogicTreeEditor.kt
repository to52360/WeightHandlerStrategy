package lin.ui.components

import javafx.geometry.Insets
import javafx.scene.control.Label
import javafx.scene.control.TreeCell
import javafx.scene.control.TreeView
import javafx.scene.layout.BorderPane
import javafx.scene.layout.VBox

interface TreeEditorBehavior<T> {
    fun install(editor: LogicTreeEditor<T>)
}

/**
 * 逻辑树通用编辑器组件
 * 泛型 T 代表具体的树节点数据模型 (例如 EvaluatorNodeWrapper)
 */
class LogicTreeEditor<T> : BorderPane() {
    val treeView = TreeView<T>()

    // 允许外部在标题栏下方注入额外的控制面板组件 (例如配置下拉框)
    val customHeaderArea = VBox(5.0)

    private val behaviors = mutableListOf<TreeEditorBehavior<T>>()

    // 提供给 Behavior 的钩子：当 TreeCell 更新时调用，防止多个 Behavior 争抢 setCellFactory
    val cellInterceptors = mutableListOf<(TreeCell<T>, T?, Boolean) -> Unit>()

    init {
        padding = Insets(10.0)

        val topBox = VBox(10.0)
        val title = Label("节点树结构").apply { style = "-fx-font-weight: bold; -fx-padding: 0 0 5 0;" }

        topBox.children.addAll(title, customHeaderArea)

        this.top = topBox
        this.center = treeView

        // 统一的 CellFactory，支持插件式 Hook
        treeView.setCellFactory {
            object : TreeCell<T>() {
                override fun updateItem(item: T?, empty: Boolean) {
                    super.updateItem(item, empty)
                    if (empty || item == null) {
                        text = null
                        graphic = null
                        contextMenu = null
                    } else {
                        text = item.toString()
                    }
                    // 广播给所有的 Behavior 进行 UI 增强 (如添加右键菜单)
                    cellInterceptors.forEach { it(this, item, empty) }
                }
            }
        }
    }

    fun addBehavior(behavior: TreeEditorBehavior<T>) {
        behaviors += behavior
        behavior.install(this)
    }
}
