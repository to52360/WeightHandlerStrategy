package lin.ui.condition_tree

import javafx.application.Platform
import javafx.scene.control.SplitPane
import lin.repository.condition_tree.ConditionTreeConfigService
import lin.rule.condition.ConditionPayload
import lin.rule.condition.ConditionRegistry
import lin.ui.ActiveAware
import lin.ui.card_group.ActiveManagerHolder
import lin.ui.components.LogicTreeEditor
import lin.ui.components.TreeConfigStrategy
import lin.ui.components.TreeEditorBehavior
import lin.ui.condition_tree.strategy.ConditionPayloadFactory
import lin.ui.condition_tree.strategy.ConditionPropertyEditorStrategy
import lin.ui.condition_tree.strategy.ConditionTreeConfigStrategy
import lin.ui.tree_config.LogicNodeType
import lin.ui.tree_config.LogicNodeWrapper
import lin.ui.tree_config.PropertyPanel
import lin.ui.tree_config.TreeModelConverter
import lin.ui.tree_config.menu.TreeContextMenuFactory
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class ConditionTreeWorkbench(
    val showList: Boolean = true
) : SplitPane(), KoinComponent, ActiveAware {

    private val conditionRegistry: ConditionRegistry by inject()
    private val conditionTreeConfigService: ConditionTreeConfigService by inject()
    private val activeManagerHolder: ActiveManagerHolder by inject()

    var targetManagerId: String? = null
        get() = field ?: activeManagerHolder.activeManagerId

    val treeConfigStrategy: TreeConfigStrategy<ConditionPayload> =
        ConditionTreeConfigStrategy(conditionTreeConfigService)

    val logicTreeEditor = LogicTreeEditor<LogicNodeWrapper<ConditionPayload>>()
    val nodeTreeView get() = logicTreeEditor.treeView

    val propertyPanel = PropertyPanel(ConditionPropertyEditorStrategy(conditionRegistry))

    private val configListPanel: ConditionTreeConfigListPanel? =
        if (showList) ConditionTreeConfigListPanel(this, treeConfigStrategy) else null

    val configListView get() = configListPanel?.configListView

    init {
        if (configListPanel != null) {
            this.items.addAll(configListPanel, logicTreeEditor, propertyPanel)
            this.setDividerPositions(0.2, 0.6)
            configListPanel.refreshList()
        } else {
            this.items.addAll(logicTreeEditor, propertyPanel)
            this.setDividerPositions(0.5)
        }

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
                        // runLater 延迟设置：规避 JavaFX cell 复用时 setContextMenu NPE（MenuItem.getParentMenu() null）
                        Platform.runLater {
                            if (cell.treeItem != null) {
                                cell.contextMenu =
                                    contextMenuFactory.createContextMenu(cell.treeItem, logicTreeEditor.treeView)
                            }
                        }
                    }
                }
            }
        })
    }

    fun refreshList() {
        configListPanel?.refreshList()
    }

    fun addDraftItem(name: String): ConditionTreeListItem? {
        return configListPanel?.addDraftItem(name)
    }

    fun initDefaultRootIfEmpty() {
        if (logicTreeEditor.treeView.root == null) {
            val rootItem = javafx.scene.control.TreeItem(
                LogicNodeWrapper<ConditionPayload>(LogicNodeType.AND)
            ).also { it.isExpanded = true }
            logicTreeEditor.treeView.root = rootItem
            logicTreeEditor.treeView.selectionModel.select(rootItem)
            propertyPanel.showNode(rootItem.value)
        } else {
            val rootItem = logicTreeEditor.treeView.root
            logicTreeEditor.treeView.selectionModel.select(rootItem)
            if (rootItem != null) {
                propertyPanel.showNode(rootItem.value)
            }
        }
    }

    fun loadExistingTree(treeId: String) {
        val loaded = treeConfigStrategy.loadAll().firstOrNull { it.id == treeId }
        if (loaded?.root != null) {
            val rootItem = TreeModelConverter.toTreeItem(loaded.root)
            logicTreeEditor.treeView.root = rootItem
            logicTreeEditor.treeView.selectionModel.select(rootItem)
            propertyPanel.showNode(rootItem.value)
        }
    }

    fun saveCurrent(name: String, existingId: String? = null, managerId: String? = null): String {
        val rootNode = logicTreeEditor.treeView.root
            ?: throw IllegalStateException("当前条件树为空，请先配置节点")
        val conditionNode = TreeModelConverter.fromTreeItem(rootNode) {
            ConditionPayload.ConditionRef(
                conditionId = "",
                refId = "empty_${System.currentTimeMillis().toString(16).takeLast(4)}"
            )
        }
        val extras = mutableMapOf<String, Any>()
        val currentSelectedItem = configListPanel?.configListView?.selectionModel?.selectedItem
        val finalManagerId = managerId
            ?: if (existingId != null && currentSelectedItem?.id == existingId) currentSelectedItem.managerId else targetManagerId
        finalManagerId?.let { extras["managerId"] = it }

        return treeConfigStrategy.save(name, conditionNode, existingId, extras)
    }

    override fun onActive() {
        refreshList()
    }
}
