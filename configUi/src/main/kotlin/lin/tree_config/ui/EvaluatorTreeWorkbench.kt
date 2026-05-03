package lin.tree_config.ui

import javafx.geometry.Insets
import javafx.scene.control.*
import javafx.scene.layout.FlowPane
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import lin.dao.CardSelectOptionProvider
import lin.rule.tree.RuleConfig
import lin.tree_config.service.TreeConfigService
import lin.tree_config.ui.action.TreeWorkbenchAction
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
    val configListView = ListView<ConfigListItem>()
    val logicTreeEditor = LogicTreeEditor<EvaluatorNodeWrapper>()
    val nodeTreeView get() = logicTreeEditor.treeView
    val groupIdComboBox = ComboBox<String>()
    val propertyPanel = PropertyPanel()

    // 选中的规则配置 (目前先只在内存中修改)
    val ruleConfigs = mutableMapOf<String, RuleConfig>()

    init {
        // 1. 左侧：配置列表区
        val listPanel = buildListPanel()

        // 2. 中间：评估树可视化操作区
        val treePanel = buildTreePanel()

        // 3. 右侧：属性编辑区 (Step 4 实现)
        val rightPanel = buildPropertyPanel()

        this.items.addAll(listPanel, treePanel, rightPanel)
        this.setDividerPositions(0.2, 0.6) // 初始化分隔条比例

        // 绑定 LogicTreeEditor 的扩展区域与事件
        val groupBox = HBox(10.0).apply { alignment = javafx.geometry.Pos.CENTER_LEFT }
        groupBox.children.addAll(Label("绑定卡组分组 (bindByGroupId):"), groupIdComboBox)
        logicTreeEditor.customHeaderArea.children.add(groupBox)

        // 注册选中节点的 Behavior —— 驱动右侧属性面板
        logicTreeEditor.addBehavior(object : TreeEditorBehavior<EvaluatorNodeWrapper> {
            override fun install(editor: LogicTreeEditor<EvaluatorNodeWrapper>) {
                editor.treeView.selectionModel.selectedItemProperty().addListener { _, _, newValue ->
                    if (newValue == null) {
                        propertyPanel.showPlaceholder()
                    } else {
                        val wrapper = newValue.value
                        when (wrapper.type) {
                            NodeType.RULE, NodeType.BRANCH -> propertyPanel.showRuleConfigNode(wrapper, ruleConfigs)
                            else -> propertyPanel.showStructureNode(wrapper.type)
                        }
                    }
                }
            }
        })

        // 注册右键菜单的 Behavior
        logicTreeEditor.addBehavior(object : TreeEditorBehavior<EvaluatorNodeWrapper> {
            override fun install(editor: LogicTreeEditor<EvaluatorNodeWrapper>) {
                editor.cellInterceptors.add { cell, item, empty ->
                    if (!empty && item != null && cell.treeItem != null) {
                        cell.contextMenu = createContextMenu(cell.treeItem)
                    }
                }
            }
        })

        // 初始化下拉选项
        try {
            //todo 这里有问题
            val options = CardSelectOptionProvider().getOptions()
            groupIdComboBox.items.addAll(options.map { it.value })
        } catch (e: Exception) {
            System.err.println("加载分组数据失败: ${e.message}")
        }

        setupListView()
        refreshList()
    }

    /**
     * 从数据库刷新列表，但保留内存中已存在的草稿条目
     */
    fun refreshList() {
        val currentDrafts = configListView.items.filter { it.isDraft }
        configListView.items.clear()
        val configs = treeConfigService.loadAll()
        configs.forEach { (entity, config) ->
            configListView.items.add(ConfigListItem(entity.id, entity.name, entity.groupId, config))
        }
        // 将未保存的草稿重新插入列表首部
        currentDrafts.forEach { configListView.items.add(0, it) }
    }

    /**
     * 向列表中插入一个内存草稿条目
     */
    fun addDraftItem(name: String): ConfigListItem {
        val draftItem = ConfigListItem(
            id = "draft_${System.currentTimeMillis()}",
            name = name,
            groupId = "",
            config = null,
            isDraft = true
        )
        configListView.items.add(0, draftItem)
        return draftItem
    }

    private fun setupListView() {
        // 草稿条目用斜体+灰色显示，已保存条目正常显示
        configListView.setCellFactory {
            object : ListCell<ConfigListItem>() {
                override fun updateItem(item: ConfigListItem?, empty: Boolean) {
                    super.updateItem(item, empty)
                    if (empty || item == null) {
                        text = null
                        style = ""
                    } else {
                        text = item.toString()
                        style = if (item.isDraft) "-fx-text-fill: gray; -fx-font-style: italic;" else ""
                    }
                }
            }
        }

        configListView.selectionModel.selectedItemProperty().addListener { _, _, newValue ->
            newValue?.let { item ->
                groupIdComboBox.selectionModel.select(item.groupId)
                if (item.config != null) {
                    logicTreeEditor.treeView.root = TreeModelConverter.toTreeItem(item.config.root)
                    ruleConfigs.clear()
                    ruleConfigs.putAll(item.config.ruleConfigs)
                }
            }
        }
    }

    private fun createContextMenu(treeItem: TreeItem<EvaluatorNodeWrapper>): ContextMenu {
        val menu = ContextMenu()
        val type = treeItem.value.type
        val isRoot = treeItem.parent == null

        // 结构节点可添加子节点
        if (type == NodeType.AND || type == NodeType.OR) {
            val addMenu = Menu("添加子节点")
            addMenu.items.addAll(
                createAddMenuItem("AND 节点", treeItem, NodeType.AND),
                createAddMenuItem("OR 节点", treeItem, NodeType.OR),
                createAddMenuItem("NOT 节点", treeItem, NodeType.NOT),
                createAddMenuItem("RULE 节点", treeItem, NodeType.RULE),
                createAddMenuItem("BRANCH 节点", treeItem, NodeType.BRANCH)
            )
            menu.items.add(addMenu)
        }

        // NOT 节点只允许添加一个子节点
        if (type == NodeType.NOT && treeItem.children.isEmpty()) {
            val addMenu = Menu("添加子节点")
            addMenu.items.addAll(
                createAddMenuItem("AND 节点", treeItem, NodeType.AND),
                createAddMenuItem("OR 节点", treeItem, NodeType.OR),
                createAddMenuItem("RULE 节点", treeItem, NodeType.RULE)
            )
            menu.items.add(addMenu)
        }

        // BRANCH 节点自动初始化 onTrue/onFalse 占位子节点
        // (在添加 BRANCH 时已处理，参见 createAddMenuItem)

        // 根节点特殊处理：提供更改根节点类型的菜单 (根节点可以是任意类型)
        if (isRoot) {
            if (menu.items.isNotEmpty()) menu.items.add(SeparatorMenuItem())
            val changeMenu = Menu("更改根节点类型")
            NodeType.entries.filter { it != type }.forEach { targetType ->
                changeMenu.items.add(MenuItem("${targetType.name} 节点").apply {
                    setOnAction {
                        treeItem.value.type = targetType
                        // BRANCH 节点需要保证至少有两个子节点
                        if (targetType == NodeType.BRANCH && treeItem.children.size < 2) {
                            repeat(2 - treeItem.children.size) {
                                treeItem.children.add(TreeItem(EvaluatorNodeWrapper(NodeType.AND)))
                            }
                        }
                        logicTreeEditor.treeView.refresh()
                    }
                })
            }
            menu.items.add(changeMenu)
        }

        // 非根节点可删除
        if (!isRoot) {
            if (menu.items.isNotEmpty()) menu.items.add(SeparatorMenuItem())
            val delete = MenuItem("删除节点")
            delete.setOnAction { treeItem.parent.children.remove(treeItem) }
            menu.items.add(delete)
        }
        return menu
    }

    private fun createAddMenuItem(text: String, parentItem: TreeItem<EvaluatorNodeWrapper>, type: NodeType): MenuItem {
        val item = MenuItem(text)
        item.setOnAction {
            val wrapper = EvaluatorNodeWrapper(type)
            when (type) {
                NodeType.RULE -> wrapper.nodeId = "rule_${System.currentTimeMillis()}"
                NodeType.BRANCH -> wrapper.nodeId = "branch_${System.currentTimeMillis()}"
                else -> {}
            }
            val newItem = TreeItem(wrapper)
            // BRANCH 节点自动预置 onTrue/onFalse 占位子节点
            if (type == NodeType.BRANCH) {
                newItem.children.addAll(
                    TreeItem(EvaluatorNodeWrapper(NodeType.AND)).also { it.isExpanded = true },
                    TreeItem(EvaluatorNodeWrapper(NodeType.AND)).also { it.isExpanded = true }
                )
            }
            parentItem.children.add(newItem)
            parentItem.isExpanded = true
        }
        return item
    }

    private fun buildListPanel(): VBox {
        val panel = VBox(5.0).apply { padding = Insets(10.0) }
        val title = Label("评估树列表").apply { style = "-fx-font-weight: bold; -fx-padding: 0 0 5 0;" }

        // 动作按钮容器 (支持自动换行)
        val buttonBox = FlowPane(5.0, 5.0)

        // 从 Koin 动态拉取所有注册的 Toolbar 动作
        val actions = getKoin().getAll<TreeWorkbenchAction>().sortedBy { it.order }
        for (action in actions) {
            val btn = Button(action.title).apply {
                setOnAction { action.execute(this@EvaluatorTreeWorkbench) }
            }
            buttonBox.children.add(btn)
        }

        // 移除硬编码 Mock
        // configListView.items.addAll("Mock Config 1", "Mock Config 2")

        panel.children.addAll(title, buttonBox, configListView)
        return panel
    }

    private fun buildTreePanel(): LogicTreeEditor<EvaluatorNodeWrapper> {
        return logicTreeEditor
    }

    private fun buildPropertyPanel(): PropertyPanel {
        propertyPanel.onRuleConfigChanged = { nodeId, config ->
            ruleConfigs[nodeId] = config
        }
        return propertyPanel
    }

}
