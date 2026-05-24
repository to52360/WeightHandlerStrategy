package lin.condition_tree.ui.action

import javafx.scene.control.Alert
import javafx.scene.control.Alert.AlertType
import javafx.scene.control.ButtonType
import javafx.scene.control.TextInputDialog
import javafx.scene.control.TreeItem
import lin.condition_tree.ui.ConditionTreeWorkbench
import lin.rule.condition.ConditionPayload
import lin.tree_config.ui.LogicNodeType
import lin.tree_config.ui.LogicNodeWrapper
import lin.tree_config.ui.TreeModelConverter

class CreateConditionTreeAction : ConditionTreeWorkbenchAction {
    override val title: String = "新建"
    override val order: Int = 10

    override fun execute(workbench: ConditionTreeWorkbench) {
        val dialog = TextInputDialog("新条件树")
        dialog.title = "新建条件树"
        dialog.headerText = "请输入新条件树的名称:"
        dialog.showAndWait().ifPresent { name ->
            if (name.isBlank()) {
                showError("名称不能为空")
                return@ifPresent
            }
            val draftItem = workbench.addDraftItem(name)

            val rootItem = TreeItem(LogicNodeWrapper<ConditionPayload>(LogicNodeType.AND)).also { it.isExpanded = true }
            workbench.nodeTreeView.root = rootItem
            workbench.propertyPanel.showPlaceholder()

            workbench.configListView.selectionModel.select(draftItem)
        }
    }
}

class SaveConditionTreeAction : ConditionTreeWorkbenchAction {
    override val title: String = "保存"
    override val order: Int = 20

    override fun execute(workbench: ConditionTreeWorkbench) {
        val selectedItem = workbench.configListView.selectionModel.selectedItem
        if (selectedItem == null) {
            showError("请先在左侧列表中选择或新建一个条件树配置")
            return
        }
        val rootNode = workbench.nodeTreeView.root
        if (rootNode == null) {
            showError("当前树为空，请先添加节点")
            return
        }

        try {
            val conditionNode = TreeModelConverter.fromTreeItem(rootNode) {
                ConditionPayload.ConditionRef(
                    conditionId = "",
                    refId = "empty_${System.currentTimeMillis().toString(16).takeLast(4)}"
                )
            }
            val savedId = if (selectedItem.isDraft) {
                workbench.treeConfigStrategy.save(selectedItem.name, conditionNode)
            } else {
                workbench.treeConfigStrategy.save(selectedItem.name, conditionNode, selectedItem.id)
            }

            if (selectedItem.isDraft) {
                workbench.configListView.items.remove(selectedItem)
            }
            workbench.refreshList()
            val savedItem = workbench.configListView.items.find { it.id == savedId }
            savedItem?.let { workbench.configListView.selectionModel.select(it) }

            showInfo("保存成功", "条件树 [${selectedItem.name}] 已成功保存")
        } catch (e: Exception) {
            showError("保存失败: ${e.message}")
        }
    }
}

class DeleteConditionTreeAction : ConditionTreeWorkbenchAction {
    override val title: String = "删除"
    override val order: Int = 30

    override fun execute(workbench: ConditionTreeWorkbench) {
        val selectedItem = workbench.configListView.selectionModel.selectedItem
        if (selectedItem == null) {
            showError("请先选择要删除的配置")
            return
        }
        val confirm = Alert(AlertType.CONFIRMATION).apply {
            title = "确认删除"
            headerText = "确认要删除 [${selectedItem.name}] 吗？"
            contentText = "此操作不可恢复"
        }.showAndWait()

        if (confirm.orElse(ButtonType.CANCEL) == ButtonType.OK) {
            try {
                workbench.treeConfigStrategy.delete(selectedItem.id)
                workbench.nodeTreeView.root = null
                workbench.propertyPanel.showPlaceholder()
                workbench.refreshList()
            } catch (e: Exception) {
                showError("删除失败: ${e.message}")
            }
        }
    }
}

private fun showError(msg: String) {
    Alert(AlertType.ERROR).apply {
        title = "错误"
        headerText = msg
    }.showAndWait()
}

private fun showInfo(title: String, msg: String) {
    Alert(AlertType.INFORMATION).apply {
        this.title = title
        headerText = msg
    }.showAndWait()
}
