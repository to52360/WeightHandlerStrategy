package lin.ui.condition_tree.ui

import javafx.scene.control.SplitPane
import lin.rule.condition.ConditionPayload
import lin.rule.condition.ConditionRegistry
import lin.ui.components.LogicTreeEditor
import lin.ui.components.TreeConfigStrategy
import lin.ui.components.TreeEditorBehavior
import lin.ui.condition_tree.db.ConditionTreeConfigService
import lin.ui.condition_tree.ui.strategy.ConditionPayloadFactory
import lin.ui.condition_tree.ui.strategy.ConditionPropertyEditorStrategy
import lin.ui.condition_tree.ui.strategy.ConditionTreeConfigStrategy
import lin.ui.tree_config.ui.LogicNodeWrapper
import lin.ui.tree_config.ui.PropertyPanel
import lin.ui.tree_config.ui.menu.TreeContextMenuFactory
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class ConditionTreeWorkbench : SplitPane(), KoinComponent {

    private val conditionRegistry: ConditionRegistry by inject()
    private val conditionTreeConfigService: ConditionTreeConfigService by inject()

    val treeConfigStrategy: TreeConfigStrategy<ConditionPayload> =
        ConditionTreeConfigStrategy(conditionTreeConfigService)

    val logicTreeEditor = LogicTreeEditor<LogicNodeWrapper<ConditionPayload>>()
    val nodeTreeView get() = logicTreeEditor.treeView

    val propertyPanel = PropertyPanel(ConditionPropertyEditorStrategy(conditionRegistry))

    private val configListPanel = ConditionTreeConfigListPanel(this, treeConfigStrategy)

    val configListView get() = configListPanel.configListView

    init {
        val listPanel = configListPanel
        val treePanel = logicTreeEditor
        val rightPanel = propertyPanel

        this.items.addAll(listPanel, treePanel, rightPanel)
        this.setDividerPositions(0.2, 0.6)

        // 注册选中节点驱动属性面板
        logicTreeEditor.addBehavior(object : TreeEditorBehavior<LogicNodeWrapper<ConditionPayload>> {
            override fun install(editor: LogicTreeEditor<LogicNodeWrapper<ConditionPayload>>) {
                editor.treeView.selectionModel.selectedItemProperty().addListener { _, _, newValue ->
                    if (newValue == null) {
                        propertyPanel.showPlaceholder()
                    } else {
                        propertyPanel.showNode(newValue.value)
                    }
                }
            }
        })

        // 注册右键菜单
        val contextMenuFactory = TreeContextMenuFactory(ConditionPayloadFactory())
        logicTreeEditor.addBehavior(object : TreeEditorBehavior<LogicNodeWrapper<ConditionPayload>> {
            override fun install(editor: LogicTreeEditor<LogicNodeWrapper<ConditionPayload>>) {
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
    fun addDraftItem(name: String) = configListPanel.addDraftItem(name)
}
