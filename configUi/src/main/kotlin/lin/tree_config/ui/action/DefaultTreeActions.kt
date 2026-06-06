package lin.tree_config.ui.action

import javafx.scene.control.Alert
import javafx.scene.control.Alert.AlertType
import javafx.scene.control.ButtonType
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
        val dialog = lin.tree_config.ui.components.TreePropertiesDialog()
        dialog.showAndWait().ifPresent { result ->
            if (result.name.isBlank()) {
                showError("名称不能为空")
                return@ifPresent
            }
            if (result.bindings.isEmpty()) {
                showError("必须选择至少一个绑定目标")
                return@ifPresent
            }

            val draftItem = workbench.addDraftItem(result.name, result.enabled, result.bindings)

            val rootItem = TreeItem(LogicNodeWrapper<EvaluatorPayload>(LogicNodeType.AND)).also { it.isExpanded = true }
            workbench.nodeTreeView.root = rootItem
            workbench.setSelectedBindings(result.bindings)
            workbench.setCurrentEnabled(result.enabled)
            workbench.leafConfigs.clear()
            workbench.propertyPanel.showPlaceholder()

            workbench.configListView.selectionModel.select(draftItem)
        }
    }
}

class EditTreePropertiesAction : TreeWorkbenchAction {
    override val title: String = "修改属性"
    override val order: Int = 15

    override fun execute(workbench: EvaluatorTreeWorkbench) {
        val selectedItem = workbench.configListView.selectionModel.selectedItem
        if (selectedItem == null) {
            showError("请先在左侧列表中选择或新建一个评估树配置")
            return
        }

        val currentBindings = workbench.getSelectedBindings()
        val currentEnabled = workbench.getCurrentEnabled()

        val dialog = lin.tree_config.ui.components.TreePropertiesDialog(
            initialName = selectedItem.name,
            initialEnabled = currentEnabled,
            initialBindings = currentBindings
        )

        dialog.showAndWait().ifPresent { result ->
            if (result.name.isBlank()) {
                showError("名称不能为空")
                return@ifPresent
            }
            if (result.bindings.isEmpty()) {
                showError("必须选择至少一个绑定目标")
                return@ifPresent
            }

            // Only update current workbench state, and list item display if it's draft.
            // For saved ones, just changing the model won't save to DB until "保存" is clicked,
            // or we could save it immediately. For simplicity, we just update the model and wait for save.

            // Actually, wait, modifying the ConfigListItem's properties directly is tricky since it's a data class.
            // Let's replace the item in the list or just let the user save it.
            // A better way is to update the Draft / selected item
            val newItem = selectedItem.copy(
                name = result.name,
                enabled = result.enabled,
                bindingsSummary = result.bindings.joinToString(",") { "${it.type.name}:${it.id}" }
            )
            val idx = workbench.configListView.items.indexOf(selectedItem)
            if (idx >= 0) {
                workbench.configListView.items[idx] = newItem
                workbench.configListView.selectionModel.select(newItem)
            }

            workbench.setSelectedBindings(result.bindings)
            workbench.setCurrentEnabled(result.enabled)
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
            val bindings = workbench.getSelectedBindings()
            if (bindings.isEmpty()) {
                showError("请选择至少一个绑定目标（分组或用途标签）")
                return
            }
            val evaluatorNode = TreeModelConverter.fromTreeItem(rootNode) { EvaluatorPayload.Rule("") }
            val config = EvaluatorTreeConfig(
                bindings = bindings,

                root = evaluatorNode,
                leafConfigs = workbench.leafConfigs.toMap()
            )
            // 草稿条目：不传入 existingId，直接新建数据库记录
            // 已保存条目：传入 existingId，执行 UPSERT
            val savedId = if (selectedItem.isDraft) {
                workbench.treeConfigService.saveConfig(selectedItem.name, config, null, workbench.getCurrentEnabled())
            } else {
                workbench.treeConfigService.saveConfig(
                    selectedItem.name,
                    config,
                    selectedItem.id,
                    workbench.getCurrentEnabled()
                )
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
                if (!selectedItem.isDraft) {
                    workbench.treeConfigService.delete(selectedItem.id)
                }
                workbench.nodeTreeView.root = null
                workbench.propertyPanel.showPlaceholder()
                workbench.configListView.items.remove(selectedItem)
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
