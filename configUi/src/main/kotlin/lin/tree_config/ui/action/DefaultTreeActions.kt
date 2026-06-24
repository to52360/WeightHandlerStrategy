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
import lin.tree_config.validation.EvaluatorTreeValidator

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

            val draftItem = workbench.addDraftItem(
                result.name, result.enabled, result.bindings,
                managerId = result.managerId, isTemplate = result.isTemplate
            )

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
            initialBindings = currentBindings,
            initialManagerId = selectedItem.managerId,
            initialIsTemplate = selectedItem.isTemplate
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

            val newItem = selectedItem.copy(
                name = result.name,
                enabled = result.enabled,
                bindingsSummary = result.bindings.joinToString(",") { "${it.type.name}:${it.id}" },
                managerId = result.managerId,
                isTemplate = result.isTemplate
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

            // ====== 统一使用 EvaluatorTreeValidator（UI 和 MCP 共用同一套验证） ======
            val evaluatorNode = TreeModelConverter.fromTreeItem(rootNode) { EvaluatorPayload.Rule("") }
            val config = EvaluatorTreeConfig(
                bindings = bindings,

                root = evaluatorNode,
                leafConfigs = workbench.leafConfigs.toMap()
            )

            val treeValidator = EvaluatorTreeValidator(workbench.leafSourceCatalog, workbench.pipelineAssembler)
            val treeReport = treeValidator.validate(config)
            if (!treeReport.ok) {
                val msg = treeReport.diagnostics.joinToString("\n") { "(${it.code}) ${it.message}" }
                showError("保存被拒绝，检测到参数配置不符合约束契约：\n\n$msg")
                return
            }
            // ================================================================
            // 草稿条目：不传入 existingId，直接新建数据库记录
            // 已保存条目：传入 existingId，执行 UPSERT
            val savedId = if (selectedItem.isDraft) {
                workbench.treeConfigService.saveConfig(
                    selectedItem.name, config, null, workbench.getCurrentEnabled(),
                    managerId = selectedItem.managerId, isTemplate = selectedItem.isTemplate
                )
            } else {
                workbench.treeConfigService.saveConfig(
                    selectedItem.name, config, selectedItem.id, workbench.getCurrentEnabled(),
                    managerId = selectedItem.managerId, isTemplate = selectedItem.isTemplate
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

/**
 * 从模板新建评估树：选择一个已有模板，复制其树结构和叶子配置为新草稿。
 */
class CreateFromTemplateAction : TreeWorkbenchAction {
    override val title: String = "从模板新建"
    override val order: Int = 12

    override fun execute(workbench: EvaluatorTreeWorkbench) {
        val managerIdSnapshot = workbench.activeManagerHolder.activeManagerId
        val templates = workbench.treeConfigService.loadTemplates()
        if (templates.isEmpty()) {
            showError("暂无可用模板，请先创建并勾选 设为模板 的评估树")
            return
        }

        // 弹出选择对话框
        val choices = templates.associateBy { it.first.name }
        val dialog = javafx.scene.control.ChoiceDialog(
            choices.keys.first(),
            choices.keys
        ).apply {
            title = "从模板新建"
            headerText = "选择一个模板作为基础创建新评估树"
            contentText = "模板:"
        }

        dialog.showAndWait().ifPresent { templateName ->
            val templatePair = choices[templateName] ?: return@ifPresent
            val (entity, config) = templatePair
            if (config == null) {
                showError("模板解析失败")
                return@ifPresent
            }

            // 复制模板结构为新草稿（名称加后缀）
            val newName = "${entity.name} 副本"
            val draftItem = workbench.addDraftItem(
                newName, entity.enabled, config.bindings,
                managerId = managerIdSnapshot,
                isTemplate = false // 从模板创建的不是模板
            )

            // 加载模板的树结构
            workbench.nodeTreeView.root = TreeModelConverter.toTreeItem(config.root)
            workbench.setSelectedBindings(config.bindings)
            workbench.setCurrentEnabled(entity.enabled)
            workbench.leafConfigs.clear()
            workbench.leafConfigs.putAll(config.leafConfigs)
            workbench.propertyPanel.showPlaceholder()

            workbench.configListView.selectionModel.select(draftItem)
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
