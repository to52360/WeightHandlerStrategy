package lin.ui

import javafx.geometry.Insets
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.layout.BorderPane
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import org.koin.core.component.KoinComponent

/**
 * 全局导航壳
 */
class MainShellView : BorderPane(), KoinComponent {
    private val workbenchArea = StackPane()
    private var currentActiveBtn: Button? = null

    init {
        // 左侧导航栏
        val navBar = VBox(10.0).apply {
            padding = Insets(20.0, 0.0, 20.0, 0.0)
            prefWidth = 200.0
            style = "-fx-background-color: #f4f4f4; -fx-border-color: #ccc; -fx-border-width: 0 1 0 0;"
        }

        // 动态加载并按 order 排序注册的所有扩展模块
        val modules = getKoin().getAll<UiExtension>().sortedBy { it.order }

        for (module in modules) {
            val btn = createNavButton(module.title) { btn ->
                val workbench = module.createWorkbench()
                switchWorkbench(workbench, btn)
            }
            navBar.children.add(btn)
        }

        // 中心工作区
        workbenchArea.padding = Insets(10.0)
        switchWorkbench(Label("请选择左侧功能模块").apply {
            style = "-fx-font-size: 18px; -fx-text-fill: #666;"
        }, null)

        this.left = navBar
        this.center = workbenchArea
    }

    private fun createNavButton(text: String, action: (Button) -> Unit): Button {
        val btn = Button(text).apply {
            maxWidth = Double.MAX_VALUE
            styleClass.add("nav-button")
        }
        btn.setOnAction { action(btn) }
        return btn
    }

    private fun switchWorkbench(node: Node, activeBtn: Button?) {
        workbenchArea.children.clear()
        workbenchArea.children.add(node)

        // 更新按钮选中状态
        currentActiveBtn?.styleClass?.remove("nav-button-selected")
        activeBtn?.styleClass?.add("nav-button-selected")
        currentActiveBtn = activeBtn
    }
}
