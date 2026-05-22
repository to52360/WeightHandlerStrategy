package lin.tree_config.ui.action

import javafx.scene.control.Alert
import javafx.scene.control.Alert.AlertType
import javafx.scene.control.ButtonType
import javafx.scene.control.TextInputDialog
import javafx.scene.control.TreeItem
import lin.rule.tree.EvaluatorPayload
import lin.rule.tree.EvaluatorTreeConfig
import lin.tree_config.ui.EvaluatorTreeWorkbench
import lin.tree_config.ui.LogicNodeType
import lin.tree_config.ui.LogicNodeWrapper
import lin.tree_config.ui.TreeModelConverter

class CreateNewTreeAction : TreeWorkbenchAction {
    override val title: String = "新建"
    override val order: Int = 10

    override fun execute(workbench: EvaluatorTreeWorkbench) {
        val dialog = TextInputDialog("新配置名称")
        dialog.title = "新建评估树"
        dialog.headerText = "请输入新评估树的名称:"
        dialog.showAndWait().ifPresent { name ->
            if (name.isBlank()) {
                showError("名称不能为空")
                return@ifPresent
            }
            // 仅在内存中创建草稿，不写入数据库
            val draftItem = workbench.addDraftItem(name)

            // 初始化编辑区
            val rootItem = TreeItem(LogicNodeWrapper<EvaluatorPayload>(LogicNodeType.AND)).also { it.isExpanded = true }
            workbench.nodeTreeView.root = rootItem
            workbench.setSelectedGroupIds(emptyList())
            workbench.leafConfigs.clear()
            workbench.propertyPanel.showPlaceholder()

            // 自动选中新草稿
            workbench.configListView.selectionModel.select(draftItem)
        }
    }
}

class SaveTreeAction : TreeWorkbenchAction {
    override val title: String = "保存"
    override val order: Int = 20

    override fun execute(workbench: EvaluatorTreeWorkbench) {
        val selectedItem = workbench.configListView.selectionModel.selectedItem
        if (selectedItem == null) {
            showError("请先在左侧列表中选择或新建一个评估树配置")
            return
        }
        val rootNode = workbench.nodeTreeView.root
        if (rootNode == null) {
            showError("当前树为空，请先添加节点")
            return
        }

        try {
            val bindGroupIds = workbench.getSelectedGroupIds()
            if (bindGroupIds.isEmpty()) {
                showError("请选择至少一个绑定卡组分组")
                return
            }
            val evaluatorNode = TreeModelConverter.fromTreeItem(rootNode) { EvaluatorPayload.Rule("") }
            val config = EvaluatorTreeConfig(
                bindGroupIds = bindGroupIds,

                root = evaluatorNode,
                leafConfigs = workbench.leafConfigs.toMap()
            )
            // 草稿条目：不传入 existingId，直接新建数据库记录
            // 已保存条目：传入 existingId，执行 UPSERT
            val savedId = if (selectedItem.isDraft) {
                workbench.treeConfigService.saveConfig(selectedItem.name, config)
            } else {
                workbench.treeConfigService.saveConfig(selectedItem.name, config, selectedItem.id)
            }

            // 将草稿从列表移除，刷新并选中已保存的正式条目
            if (selectedItem.isDraft) {
                workbench.configListView.items.remove(selectedItem)
            }
            workbench.refreshList()
            val savedItem = workbench.configListView.items.find { it.id == savedId }
            savedItem?.let { workbench.configListView.selectionModel.select(it) }

            showInfo("保存成功", "评估树 [${selectedItem.name}] 已成功保存")
        } catch (e: Exception) {
            showError("保存失败: ${e.message}")
        }
    }
}

class DeleteTreeAction : TreeWorkbenchAction {
    override val title: String = "删除"
    override val order: Int = 30

    override fun execute(workbench: EvaluatorTreeWorkbench) {
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
                workbench.treeConfigService.delete(selectedItem.id)
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
