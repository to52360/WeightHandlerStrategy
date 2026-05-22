package lin.tree_config.ui


import javafx.scene.control.SplitPane
import lin.rule.tree.EvaluatorLeafConfig
import lin.rule.tree.EvaluatorPayload
import lin.tree_config.service.TreeConfigService
import lin.tree_config.ui.components.BindGroupSelector
import lin.tree_config.ui.components.ConfigListPanel
import lin.tree_config.ui.menu.TreeContextMenuFactory
import lin.ui.components.LogicTreeEditor
import lin.ui.components.TreeEditorBehavior
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * 评估树配置工作台的主界面骨架
 */
class EvaluatorTreeWorkbench : SplitPane(), KoinComponent {

    // 依赖注入
    val treeConfigService: TreeConfigService by inject()

    // 暴露核心 UI 组件供 Action 访问上下文状态
    val logicTreeEditor = LogicTreeEditor<LogicNodeWrapper<EvaluatorPayload>>()
    val nodeTreeView get() = logicTreeEditor.treeView

    val propertyPanel = PropertyPanel()

    // 选中的叶子配置 (目前先只在内存中修改)
    val leafConfigs = mutableMapOf<String, EvaluatorLeafConfig>()

    private val bindGroupSelector = BindGroupSelector()
    private val configListPanel = ConfigListPanel(this, treeConfigService)

    val configListView get() = configListPanel.configListView

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
        logicTreeEditor.customHeaderArea.children.add(bindGroupSelector)

        // 注册选中节点的 Behavior —— 驱动右侧属性面板
        logicTreeEditor.addBehavior(object : TreeEditorBehavior<LogicNodeWrapper<EvaluatorPayload>> {
            override fun install(editor: LogicTreeEditor<LogicNodeWrapper<EvaluatorPayload>>) {
                editor.treeView.selectionModel.selectedItemProperty().addListener { _, _, newValue ->
                    if (newValue == null) {
                        propertyPanel.showPlaceholder()
                    } else {
                        val wrapper = newValue.value
                        when (wrapper.type) {
                            LogicNodeType.LEAF, LogicNodeType.BRANCH -> propertyPanel.showRuleConfigNode(
                                wrapper,
                                leafConfigs
                            )
                            else -> propertyPanel.showStructureNode(wrapper.type)
                        }
                    }
                }
            }
        })

        // 注册右键菜单的 Behavior
        logicTreeEditor.addBehavior(object : TreeEditorBehavior<LogicNodeWrapper<EvaluatorPayload>> {
            override fun install(editor: LogicTreeEditor<LogicNodeWrapper<EvaluatorPayload>>) {
                editor.cellInterceptors.add { cell, item, empty ->
                    if (!empty && item != null && cell.treeItem != null) {
                        cell.contextMenu =
                            TreeContextMenuFactory.createContextMenu(cell.treeItem, logicTreeEditor.treeView)
                    }
                }
            }
        })

        configListPanel.refreshList()
    }

    fun refreshList() = configListPanel.refreshList()
    fun addDraftItem(name: String) = configListPanel.addDraftItem(name)
    fun getSelectedGroupIds() = bindGroupSelector.getSelectedGroupIds()
    fun setSelectedGroupIds(ids: List<String>) = bindGroupSelector.setSelectedGroupIds(ids)

    private fun buildTreePanel(): LogicTreeEditor<LogicNodeWrapper<EvaluatorPayload>> {
        return logicTreeEditor
    }

    private fun buildPropertyPanel(): PropertyPanel {
        propertyPanel.onRuleConfigChanged = { nodeId, config ->
            leafConfigs[nodeId] = config
        }
        return propertyPanel
    }

}
