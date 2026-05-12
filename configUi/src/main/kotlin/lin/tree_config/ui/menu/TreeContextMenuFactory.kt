package lin.tree_config.ui.menu

import javafx.scene.control.*
import lin.rule.tree.EvaluatorPayload
import lin.tree_config.ui.LogicNodeType
import lin.tree_config.ui.LogicNodeWrapper

object TreeContextMenuFactory {

    fun createContextMenu(
        treeItem: TreeItem<LogicNodeWrapper<EvaluatorPayload>>,
        treeView: TreeView<LogicNodeWrapper<EvaluatorPayload>>
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
                createAddMenuItem("RULE 节点", treeItem, LogicNodeType.LEAF),
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
                createAddMenuItem("RULE 节点", treeItem, LogicNodeType.LEAF)
            )
            menu.items.add(addMenu)
        }

        // 节点类型替换功能：允许所有节点（包括 Root 和 Branch 的子节点）更改类型
        if (menu.items.isNotEmpty()) menu.items.add(SeparatorMenuItem())
        val changeMenu = Menu(if (isRoot) "更改根节点类型" else "更改节点类型")
        LogicNodeType.entries.filter { it != type }.forEach { targetType ->
            changeMenu.items.add(MenuItem("${targetType.name} 节点").apply {
                setOnAction {
                    treeItem.value.type = targetType

                    // 1. 根据节点类型分配或清空 nodeId
                    if (targetType == LogicNodeType.LEAF || targetType == LogicNodeType.BRANCH) {
                        val prefix = if (targetType == LogicNodeType.LEAF) "rule" else "branch"
                        val id = "${prefix}_${System.currentTimeMillis()}"
                        treeItem.value.payload =
                            if (targetType == LogicNodeType.LEAF) EvaluatorPayload.Rule(id) else EvaluatorPayload.BranchCondition(
                                id
                            )
                    } else {
                        treeItem.value.payload = null
                    }

                    // 2. 根据目标节点类型处理已有的子节点
                    when (targetType) {
                        LogicNodeType.LEAF -> {
                            // LEAF 节点不能有子节点
                            treeItem.children.clear()
                        }

                        LogicNodeType.NOT -> {
                            // NOT 节点最多只能有一个子节点
                            if (treeItem.children.size > 1) {
                                val first = treeItem.children.first()
                                treeItem.children.clear()
                                treeItem.children.add(first)
                            }
                        }

                        LogicNodeType.BRANCH -> {
                            // BRANCH 节点强制清空并重建两个占位子节点（onTrue 和 onFalse）
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

                    treeView.refresh()
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

        // 非根节点可删除，但 Branch 的直接子节点（onTrue/onFalse）不允许删除，只能通过“更改节点类型”替换
        val parentType = treeItem.parent?.value?.type
        if (!isRoot && parentType != LogicNodeType.BRANCH) {
            if (menu.items.isNotEmpty()) menu.items.add(SeparatorMenuItem())
            val delete = MenuItem("删除节点")
            delete.setOnAction { treeItem.parent.children.remove(treeItem) }
            menu.items.add(delete)
        }
        return menu
    }

    private fun createAddMenuItem(
        text: String,
        parentItem: TreeItem<LogicNodeWrapper<EvaluatorPayload>>,
        type: LogicNodeType
    ): MenuItem {
        val item = MenuItem(text)
        item.setOnAction {
            val wrapper = LogicNodeWrapper<EvaluatorPayload>(type)
            when (type) {
                LogicNodeType.LEAF -> wrapper.payload = EvaluatorPayload.Rule("rule_${System.currentTimeMillis()}")
                LogicNodeType.BRANCH -> wrapper.payload =
                    EvaluatorPayload.BranchCondition("branch_${System.currentTimeMillis()}")

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
            parentItem.isExpanded = true
        }
        return item
    }

    private fun createLeafPlaceholder(suffix: String): TreeItem<LogicNodeWrapper<EvaluatorPayload>> {
        val wrapper = LogicNodeWrapper<EvaluatorPayload>(LogicNodeType.LEAF)
        wrapper.payload = EvaluatorPayload.Rule("rule_${System.currentTimeMillis()}_$suffix")
        return TreeItem(wrapper).also { it.isExpanded = true }
    }
}
