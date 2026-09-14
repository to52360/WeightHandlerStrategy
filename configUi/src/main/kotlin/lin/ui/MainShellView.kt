package lin.ui

import javafx.geometry.Insets
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import lin.repository.card_group.CardGroupRepository
import lin.repository.card_group.CardManagerEntity
import lin.ui.card_group.ActiveManagerHolder
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

        // 全局导航器接入（支持跨模块跳转并携带上下文）
        val navigator = getKoin().getOrNull<WorkbenchNavigator>()
        navigator?.onNavigate = { title, context ->
            val targetModule = modules.find { it.title == title }
            if (targetModule == null) {
                // 跳转目标不存在属于接线错误（魔法字符串/未注册扩展），必须显式告警而非静默吞掉
                println("[MainShellView] 导航目标不存在: \"$title\"（请检查跳转方是否引用了扩展的 TITLE 常量）")
            } else {
                val targetBtn = navBar.children.filterIsInstance<Button>().find { it.text == title }
                val workbench = targetModule.createWorkbench()
                switchWorkbench(workbench, targetBtn)
                // 上下文解释权归目标扩展（applyContext），壳层不感知具体工作台类型
                targetModule.applyContext(workbench, context)
            }
        }

        // 顶部卡组选择器
        this.top = createManagerSelector()

        this.left = navBar
        this.center = workbenchArea
    }

    private fun createManagerSelector(): HBox {
        val holder = getKoin().get<ActiveManagerHolder>()
        val repo = getKoin().get<CardGroupRepository>()

        val comboBox = ComboBox<CardManagerEntity>().apply {
            promptText = "选择卡组方案"
            buttonCell = object : javafx.scene.control.ListCell<CardManagerEntity>() {
                override fun updateItem(item: CardManagerEntity?, empty: Boolean) {
                    super.updateItem(item, empty)
                    text = if (empty || item == null) "" else item.name
                }
            }
            cellFactory = javafx.util.Callback {
                object : javafx.scene.control.ListCell<CardManagerEntity>() {
                    override fun updateItem(item: CardManagerEntity?, empty: Boolean) {
                        super.updateItem(item, empty)
                        text = if (empty || item == null) "" else "${item.name}${if (item.enabled) "" else " (已禁用)"}"
                    }
                }
            }
        }

        // 加载所有 Manager
        val managers = repo.findAllManagers()
        comboBox.items.setAll(managers)

        // 选中时同步到 ActiveManagerHolder
        comboBox.valueProperty().addListener { _, _, newValue ->
            holder.activeManager = newValue
        }

        return HBox(10.0).apply {
            padding = Insets(8.0, 10.0, 8.0, 10.0)
            style = "-fx-background-color: #e8e8e8; -fx-border-color: #ccc; -fx-border-width: 0 0 1 0;"
            children.addAll(
                Label("当前卡组方案:").apply { style = "-fx-font-size: 13px; -fx-padding: 3 0 0 0;" },
                comboBox
            )
        }
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

        // 如果视图支持激活生命周期，显式触发其数据或副作用加载
        if (node is ActiveAware) {
            node.onActive()
        }

        // 更新按钮选中状态
        currentActiveBtn?.styleClass?.remove("nav-button-selected")
        activeBtn?.styleClass?.add("nav-button-selected")
        currentActiveBtn = activeBtn
    }
}
