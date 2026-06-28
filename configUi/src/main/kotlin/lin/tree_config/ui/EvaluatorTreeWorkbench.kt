package lin.tree_config.ui

import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.SplitPane
import javafx.scene.layout.HBox
import lin.card_purpose.PurposeTagProvider
import lin.rule.tree.EvaluatorLeafConfig
import lin.rule.tree.EvaluatorPayload
import lin.rule.tree.EvaluatorTreeBindingType
import lin.tree_config.ui.components.ConfigListPanel
import lin.tree_config.ui.menu.TreeContextMenuFactory
import lin.tree_config.ui.strategy.EvaluatorPayloadFactory
import lin.tree_config.ui.strategy.EvaluatorPropertyEditorStrategy
import lin.ui.components.LogicTreeEditor
import lin.ui.components.TreeEditorBehavior
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * 评估树配置工作台的主界面骨架
 */
class EvaluatorTreeWorkbench : SplitPane(), KoinComponent {

    private val tagProvider: PurposeTagProvider by inject()

    // 选中的叶子配置 (目前先只在内存中修改)
    val leafConfigs = mutableMapOf<String, EvaluatorLeafConfig>()

    val logicTreeEditor = LogicTreeEditor<LogicNodeWrapper<EvaluatorPayload>>()
    val nodeTreeView get() = logicTreeEditor.treeView

    val propertyPanel = PropertyPanel(EvaluatorPropertyEditorStrategy(leafConfigs))

    private val configListPanel = ConfigListPanel(this)

    val configListView get() = configListPanel.configListView

    private var selectionState = SelectionState()

    private val statusLabel = Label("未选择评估树")
    private val editPropsBtn = Button("修改属性").apply {
        isDisable = true
    }

    init {
        // 三栏布局
        this.items.addAll(configListPanel, logicTreeEditor, propertyPanel)
        this.setDividerPositions(0.2, 0.6)

        // 绑定 LogicTreeEditor 的扩展区域与事件
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
    }

    fun refreshList() = configListPanel.refreshList()
    fun addDraftItem(
        name: String,
        enabled: Boolean,
        bindingType: EvaluatorTreeBindingType,
        bindingIds: List<String>,
        managerId: String? = null,
        isTemplate: Boolean = false
    ) =
        configListPanel.addDraftItem(name, enabled, bindingType, bindingIds, managerId, isTemplate)

    fun getSelectedBindings(): Pair<EvaluatorTreeBindingType?, List<String>> =
        selectionState.bindingType to selectionState.bindingIds

    fun updateSelectionState(type: EvaluatorTreeBindingType, ids: List<String>, enabled: Boolean) {
        selectionState = SelectionState(type, ids, enabled)
        updateHeader()
    }

    fun getCurrentEnabled() = selectionState.enabled
    fun setCurrentEnabled(enabled: Boolean) {
        selectionState = selectionState.copy(enabled = enabled)
        updateHeader()
    }

    fun updateHeader() {
        val selectedItem = configListView.selectionModel.selectedItem
        if (selectedItem == null) {
            statusLabel.text = "未选择评估树"
            editPropsBtn.isDisable = true
        } else {
            val s = selectionState
            val statusStr = if (s.enabled) "启用" else "禁用"
            val bindingsStr = when (s.bindingType) {
                EvaluatorTreeBindingType.PURPOSE_TAG -> s.bindingIds.joinToString(", ") { id ->
                    tagProvider.displayName(id)
                }

                EvaluatorTreeBindingType.GROUP -> s.bindingIds.joinToString(", ")
                null -> "无绑定"
            }
            statusLabel.text = "当前: ${selectedItem.name} | 状态: $statusStr | 绑定: $bindingsStr"
            editPropsBtn.isDisable = false
        }
    }
}

/** 当前选中配置的状态 */
private data class SelectionState(
    val bindingType: EvaluatorTreeBindingType? = null,
    val bindingIds: List<String> = emptyList(),
    val enabled: Boolean = true
)
