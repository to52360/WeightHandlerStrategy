package lin.ui.tree_config.menu

import javafx.scene.control.*
import lin.ui.components.PayloadFactory
import lin.ui.tree_config.LogicNodeType
import lin.ui.tree_config.LogicNodeWrapper

class TreeContextMenuFactory<L>(
    private val payloadFactory: PayloadFactory<L>,
    private val titleResolver: ((L?) -> String)? = null
) {

    fun createContextMenu(
        treeItem: TreeItem<LogicNodeWrapper<L>>,
        treeView: TreeView<LogicNodeWrapper<L>>
    ): ContextMenu {
        val menu = ContextMenu()
        val type = treeItem.value.type
        val isRoot = treeItem.parent == null

        // 结构节点可添加子节点
        if (type == LogicNodeType.AND || type == LogicNodeType.OR) {
            val addMenu = Menu("添加子节点")
            addMenu.items.addAll(
                createAddMenuItem("AND 节点", treeItem, LogicNodeType.AND),
                createAddMenuItem("OR 节点", treeItem, LogicNodeType.OR),
                createAddMenuItem("NOT 节点", treeItem, LogicNodeType.NOT),
                createAddMenuItem("LEAF 节点", treeItem, LogicNodeType.LEAF),
                createAddMenuItem("BRANCH 节点", treeItem, LogicNodeType.BRANCH)
            )
            menu.items.add(addMenu)
        }

        // NOT 节点只允许添加一个子节点
        if (type == LogicNodeType.NOT && treeItem.children.isEmpty()) {
            val addMenu = Menu("添加子节点")
            addMenu.items.addAll(
                createAddMenuItem("AND 节点", treeItem, LogicNodeType.AND),
                createAddMenuItem("OR 节点", treeItem, LogicNodeType.OR),
                createAddMenuItem("LEAF 节点", treeItem, LogicNodeType.LEAF)
            )
            menu.items.add(addMenu)
        }

        // 重命名
        if (menu.items.isNotEmpty()) menu.items.add(SeparatorMenuItem())
        val renameItem = MenuItem("重命名")
        renameItem.setOnAction {
            val dialog =
                javafx.scene.control.TextInputDialog(treeItem.value.customName ?: treeItem.value.toString()).apply {
                    title = "重命名节点"
                    headerText = "请输入节点的新名称"
                    contentText = "名称:"
                }
            dialog.showAndWait().ifPresent { newName ->
                val trimmed = newName.trim()
                treeItem.value.customName = if (trimmed.isBlank()) null else trimmed
                treeView.refresh()
            }
        }
        menu.items.add(renameItem)

        // 节点类型替换功能：允许所有节点（包括 Root 和 Branch 的子节点）更改类型
        menu.items.add(SeparatorMenuItem())
        val changeMenu = Menu(if (isRoot) "更改根节点类型" else "更改节点类型")
        LogicNodeType.entries.filter { it != type }.forEach { targetType ->
            changeMenu.items.add(MenuItem("${targetType.name} 节点").apply {
                setOnAction {
                    treeItem.value.type = targetType

                    // 1. 根据节点类型分配或清空 payload
                    if (targetType == LogicNodeType.LEAF || targetType == LogicNodeType.BRANCH) {
                        treeItem.value.payload =
                            if (targetType == LogicNodeType.LEAF) payloadFactory.createEmptyLeaf()
                            else payloadFactory.createEmptyBranch()
                    } else {
                        treeItem.value.payload = null
                    }

                    // 2. 根据目标节点类型处理已有的子节点
                    when (targetType) {
                        LogicNodeType.LEAF -> {
                            treeItem.children.clear()
                        }

                        LogicNodeType.NOT -> {
                            if (treeItem.children.size > 1) {
                                val first = treeItem.children.first()
                                treeItem.children.clear()
                                treeItem.children.add(first)
                            }
                        }

                        LogicNodeType.BRANCH -> {
                            treeItem.children.clear()
                            treeItem.children.addAll(
                                createLeafPlaceholder("true"),
                                createLeafPlaceholder("false")
                            )
                        }

                        LogicNodeType.AND, LogicNodeType.OR -> {
                            // 保持现有子节点不变
                        }
                    }

                    afterChildrenChanged(treeItem, treeView)
                    // 触发重新选中以刷新右侧面板
                    val selectionModel = treeView.selectionModel
                    if (selectionModel.selectedItem == treeItem) {
                        selectionModel.clearSelection()
                        selectionModel.select(treeItem)
                    }
                }
            })
        }
        menu.items.add(changeMenu)

        // 非根节点可删除，但 Branch 的直接子节点（onTrue/onFalse）不允许删除，只能通过"更改节点类型"替换
        val parentType = treeItem.parent?.value?.type
        if (!isRoot && parentType != LogicNodeType.BRANCH) {
            if (menu.items.isNotEmpty()) menu.items.add(SeparatorMenuItem())
            val delete = MenuItem("删除节点")
            delete.setOnAction {
                val parent = treeItem.parent
                parent.children.remove(treeItem)
                parent.value.childrenCount = parent.children.size
                treeView.refresh()
            }
            menu.items.add(delete)
        }
        return menu
    }

    private fun createAddMenuItem(
        text: String,
        parentItem: TreeItem<LogicNodeWrapper<L>>,
        type: LogicNodeType
    ): MenuItem {
        val item = MenuItem(text)
        item.setOnAction {
            val wrapper = LogicNodeWrapper<L>(type, titleResolver = titleResolver)
            when (type) {
                LogicNodeType.LEAF -> wrapper.payload = payloadFactory.createEmptyLeaf()
                LogicNodeType.BRANCH -> wrapper.payload = payloadFactory.createEmptyBranch()
                else -> {}
            }
            val newItem = TreeItem(wrapper)
            // BRANCH 节点自动预置 onTrue/onFalse 占位子节点
            if (type == LogicNodeType.BRANCH) {
                newItem.children.addAll(
                    createLeafPlaceholder("true"),
                    createLeafPlaceholder("false")
                )
            }
            parentItem.children.add(newItem)
            parentItem.value.childrenCount = parentItem.children.size
            parentItem.isExpanded = true
        }
        return item
    }

    private fun createLeafPlaceholder(suffix: String): TreeItem<LogicNodeWrapper<L>> {
        val wrapper = LogicNodeWrapper<L>(LogicNodeType.LEAF, titleResolver = titleResolver)
        wrapper.payload = payloadFactory.createEmptyLeaf()
        return TreeItem(wrapper).also { it.isExpanded = true }
    }

    /** 节点类型变更后同步 childrenCount 并刷新 */
    private fun afterChildrenChanged(treeItem: TreeItem<LogicNodeWrapper<L>>, treeView: TreeView<LogicNodeWrapper<L>>) {
        treeItem.value.childrenCount = treeItem.children.size
        treeView.refresh()
    }
}
