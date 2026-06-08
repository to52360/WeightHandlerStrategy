package lin.tree_config.ui


import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.SplitPane
import javafx.scene.layout.HBox
import lin.card_group.ui.ActiveManagerHolder
import lin.card_purpose.PurposeTagProvider
import lin.rule.tree.EvaluatorLeafConfig
import lin.rule.tree.EvaluatorPayload
import lin.rule.tree.EvaluatorTreeBinding
import lin.rule.tree.EvaluatorTreeBindingType
import lin.tree_config.db.EvaluatorLeafSourceCatalog
import lin.tree_config.ui.components.ConfigListPanel
import lin.tree_config.ui.menu.TreeContextMenuFactory
import lin.tree_config.ui.strategy.EvaluatorPayloadFactory
import lin.tree_config.ui.strategy.EvaluatorPropertyEditorStrategy
import lin.ui.components.LogicTreeEditor
import lin.ui.components.TreeEditorBehavior
import lin.ui.service.TreeConfigService
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * 评估树配置工作台的主界面骨架
 */
class EvaluatorTreeWorkbench : SplitPane(), KoinComponent {

    // 依赖注入
    val treeConfigService: TreeConfigService by inject()
    private val tagProvider: PurposeTagProvider by inject()
    private val leafSourceCatalog: EvaluatorLeafSourceCatalog by inject()
    val activeManagerHolder: ActiveManagerHolder by inject()

    // 选中的叶子配置 (目前先只在内存中修改)
    val leafConfigs = mutableMapOf<String, EvaluatorLeafConfig>()

    // 暴露核心 UI 组件供 Action 访问上下文状态
    val logicTreeEditor = LogicTreeEditor<LogicNodeWrapper<EvaluatorPayload>>()
    val nodeTreeView get() = logicTreeEditor.treeView

    val propertyPanel = PropertyPanel(EvaluatorPropertyEditorStrategy(leafSourceCatalog, leafConfigs))

    private val configListPanel = ConfigListPanel(this, treeConfigService)

    val configListView get() = configListPanel.configListView

    private var currentBindings: List<EvaluatorTreeBinding> = emptyList()
    private var currentEnabled: Boolean = true

    private val statusLabel = Label("未选择评估树")
    private val editPropsBtn = Button("修改属性").apply {
        isDisable = true
    }

    init {
        // 1. 左侧：配置列表区
        val listPanel = configListPanel

        // 2. 中间：评估树可视化操作区
        val treePanel = buildTreePanel()

        // 3. 右侧：属性编辑区 (Step 4 实现)
        val rightPanel = buildPropertyPanel()

        this.items.addAll(listPanel, treePanel, rightPanel)
        this.setDividerPositions(0.2, 0.6) // 初始化分隔条比例

        //  绑定 LogicTreeEditor 的扩展区域与事件
        val headerBox = HBox(10.0).apply {
            alignment = Pos.CENTER_LEFT
            style = "-fx-padding: 5;"
        }
        editPropsBtn.setOnAction {
            val action = getKoin().getAll<lin.tree_config.ui.action.TreeWorkbenchAction>()
                .find { it.title == "属性" || it.title == "修改" || it.title == "修改属性" }
            action?.execute(this@EvaluatorTreeWorkbench)
        }
        headerBox.children.addAll(statusLabel, editPropsBtn)
        logicTreeEditor.customHeaderArea.children.add(headerBox)

        // 注册选中节点的 Behavior —— 驱动右侧属性面板
        logicTreeEditor.addBehavior(object : TreeEditorBehavior<LogicNodeWrapper<EvaluatorPayload>> {
            override fun install(editor: LogicTreeEditor<LogicNodeWrapper<EvaluatorPayload>>) {
                editor.treeView.selectionModel.selectedItemProperty().addListener { _, _, newValue ->
                    if (newValue == null) {
                        propertyPanel.showPlaceholder()
                    } else {
                        propertyPanel.showNode(newValue.value)
                    }
                }
            }
        })

        // 注册右键菜单的 Behavior
        val contextMenuFactory = TreeContextMenuFactory(EvaluatorPayloadFactory())
        logicTreeEditor.addBehavior(object : TreeEditorBehavior<LogicNodeWrapper<EvaluatorPayload>> {
            override fun install(editor: LogicTreeEditor<LogicNodeWrapper<EvaluatorPayload>>) {
                editor.cellInterceptors.add { cell, item, empty ->
                    if (!empty && item != null && cell.treeItem != null) {
                        cell.contextMenu =
                            contextMenuFactory.createContextMenu(cell.treeItem, logicTreeEditor.treeView)
                    }
                }
            }
        })

        configListPanel.refreshList()

        // 监听当前 manager 切换，自动刷新列表
        activeManagerHolder.activeManagerProperty.addListener { _, _, _ ->
            refreshList()
        }
    }

    fun refreshList() = configListPanel.refreshList()
    fun addDraftItem(
        name: String,
        enabled: Boolean,
        bindings: List<EvaluatorTreeBinding>,
        managerId: String? = null,
        isTemplate: Boolean = false
    ) =
        configListPanel.addDraftItem(name, enabled, bindings, managerId, isTemplate)

    fun getSelectedBindings(): List<EvaluatorTreeBinding> = currentBindings
    fun setSelectedBindings(bindings: List<EvaluatorTreeBinding>) {
        currentBindings = bindings
        updateHeader()
    }

    fun getCurrentEnabled() = currentEnabled
    fun setCurrentEnabled(enabled: Boolean) {
        currentEnabled = enabled
        updateHeader()
    }

    fun updateHeader() {
        val selectedItem = configListView.selectionModel.selectedItem
        if (selectedItem == null) {
            statusLabel.text = "未选择评估树"
            editPropsBtn.isDisable = true
        } else {
            val statusStr = if (currentEnabled) "启用" else "禁用"
            val bindingsStr = currentBindings.joinToString(", ") { binding ->
                when (binding.type) {
                    EvaluatorTreeBindingType.PURPOSE_TAG -> tagProvider.displayName(binding.id)
                    else -> binding.id
                }
            }.ifEmpty { "无绑定" }
            statusLabel.text = "当前: ${selectedItem.name} | 状态: $statusStr | 绑定: $bindingsStr"
            editPropsBtn.isDisable = false
        }
    }

    private fun buildTreePanel(): LogicTreeEditor<LogicNodeWrapper<EvaluatorPayload>> {
        return logicTreeEditor
    }

    private fun buildPropertyPanel(): PropertyPanel<EvaluatorPayload> {
        return propertyPanel
    }

}
