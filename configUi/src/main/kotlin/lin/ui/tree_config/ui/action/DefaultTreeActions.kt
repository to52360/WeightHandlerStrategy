package lin.ui.tree_config.ui.action

import javafx.scene.control.Alert
import javafx.scene.control.Alert.AlertType
import javafx.scene.control.ButtonType
import javafx.scene.control.TreeItem
import lin.rule.condition.PipelineAssembler
import lin.rule.tree.EvaluatorPayload
import lin.rule.tree.EvaluatorTreeBindingType
import lin.rule.tree.EvaluatorTreeConfig
import lin.ui.service.EvaluatorTreeTemplateService
import lin.ui.service.TreeConfigService
import lin.ui.tree_config.db.EvaluatorLeafSourceCatalog
import lin.ui.tree_config.ui.EvaluatorTreeWorkbench
import lin.ui.tree_config.ui.LogicNodeType
import lin.ui.tree_config.ui.LogicNodeWrapper
import lin.ui.tree_config.ui.TreeModelConverter
import lin.ui.tree_config.ui.components.TreePropertiesDialog
import lin.ui.tree_config.validation.EvaluatorTreeValidator
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class CreateNewTreeAction : TreeWorkbenchAction {
    override val title: String = "新建"
    override val order: Int = 10

    override fun execute(workbench: EvaluatorTreeWorkbench) {
        val dialog = TreePropertiesDialog()
        dialog.showAndWait().ifPresent { result ->
            if (result.name.isBlank()) {
                showError("名称不能为空")
                return@ifPresent
            }
            if (result.bindingIds.isEmpty()) {
                showError("必须选择至少一个绑定目标")
                return@ifPresent
            }

            val draftItem = workbench.addDraftItem(
                result.name, result.description, result.enabled, result.bindingType, result.bindingIds,
                managerId = result.managerId
            )

            val rootItem = TreeItem(
                LogicNodeWrapper<EvaluatorPayload>(
                LogicNodeType.AND,
                titleResolver = { p ->
                    val nodeId = when (p) {
                        is EvaluatorPayload.Rule -> p.nodeId
                        is EvaluatorPayload.BranchCondition -> p.nodeId
                        else -> null
                    }
                    if (nodeId != null) {
                        val leaf = workbench.leafConfigs[nodeId]
                        leaf?.sourceId ?: nodeId
                    } else {
                        p?.toString() ?: "?"
                    }
                }
            )).also { it.isExpanded = true }
            workbench.nodeTreeView.root = rootItem
            workbench.updateSelectionState(result.bindingType, result.bindingIds, result.enabled)
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

        val (currentBindingType, currentBindingIds) = workbench.getSelectedBindings()
        val currentEnabled = workbench.getCurrentEnabled()

        val dialog = TreePropertiesDialog(
            initialName = selectedItem.name,
            initialDescription = selectedItem.description,
            initialEnabled = currentEnabled,
            initialBindingType = currentBindingType,
            initialBindingIds = currentBindingIds,
            initialManagerId = selectedItem.managerId
        )

        dialog.showAndWait().ifPresent { result ->
            if (result.name.isBlank()) {
                showError("名称不能为空")
                return@ifPresent
            }
            if (result.bindingIds.isEmpty()) {
                showError("必须选择至少一个绑定目标")
                return@ifPresent
            }

            val newItem = selectedItem.copy(
                name = result.name,
                description = result.description,
                enabled = result.enabled,
                bindingIds = result.bindingIds.joinToString(","),
                managerId = result.managerId
            )
            val idx = workbench.configListView.items.indexOf(selectedItem)
            if (idx >= 0) {
                workbench.configListView.items[idx] = newItem
                workbench.configListView.selectionModel.select(newItem)
            }

            workbench.updateSelectionState(result.bindingType, result.bindingIds, result.enabled)
        }
    }
}

class SaveTreeAction : TreeWorkbenchAction, KoinComponent {
    override val title: String = "保存"
    override val order: Int = 20

    private val pipelineAssembler: PipelineAssembler by inject()
    private val leafSourceCatalog: EvaluatorLeafSourceCatalog by inject()
    private val treeConfigService: TreeConfigService by inject()

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
            val (bindingType, bindingIds) = workbench.getSelectedBindings()
            if (bindingIds.isEmpty()) {
                showError("请选择至少一个绑定目标（分组或用途标签）")
                return
            }

            // ====== 统一使用 EvaluatorTreeValidator（UI 和 MCP 共用同一套验证） ======
            val evaluatorNode = TreeModelConverter.fromTreeItem(rootNode) { EvaluatorPayload.Rule("") }
            val config = EvaluatorTreeConfig(
                bindingType = bindingType ?: EvaluatorTreeBindingType.GROUP,
                bindingIds = bindingIds,
                root = evaluatorNode,
                leafConfigs = workbench.leafConfigs.toMap()
            )

            val treeValidator = EvaluatorTreeValidator(leafSourceCatalog, pipelineAssembler)
            val treeReport = treeValidator.validate(
                config = config,
                name = selectedItem.name,
                managerId = selectedItem.managerId,
                requireMetadata = true
            )
            if (!treeReport.ok) {
                val msg = treeReport.diagnostics.joinToString("\n") { "(${it.code}) ${it.message}" }
                showError("保存被拒绝，检测到配置参数不符合约束契约：\n\n$msg")
                return
            }
            // ================================================================
            // 草稿条目：不传入 existingId，直接新建数据库记录
            // 已保存条目：传入 existingId，执行 UPSERT
            val savedId = if (selectedItem.isDraft) {
                treeConfigService.saveConfig(
                    selectedItem.name, config, null, workbench.getCurrentEnabled(),
                    managerId = selectedItem.managerId,
                    description = selectedItem.description
                )
            } else {
                treeConfigService.saveConfig(
                    selectedItem.name, config, selectedItem.id, workbench.getCurrentEnabled(),
                    managerId = selectedItem.managerId,
                    description = selectedItem.description
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

class SaveAsTemplateAction : TreeWorkbenchAction, KoinComponent {
    override val title: String = "存为模板"
    override val order: Int = 25

    private val templateService: EvaluatorTreeTemplateService by inject()
    private val pipelineAssembler: PipelineAssembler by inject()
    private val leafSourceCatalog: EvaluatorLeafSourceCatalog by inject()

    override fun execute(workbench: EvaluatorTreeWorkbench) {
        val rootNode = workbench.nodeTreeView.root
        if (rootNode == null) {
            showError("当前树为空，无法保存为模板")
            return
        }
        val selectedItem = workbench.configListView.selectionModel.selectedItem ?: return

        val dialog = javafx.scene.control.TextInputDialog("${selectedItem.name} 模板").apply {
            title = "存为模板"
            headerText = "请输入新模板的名称"
            contentText = "模板名称:"
        }

        dialog.showAndWait().ifPresent { templateName ->
            val name = templateName.trim()
            if (name.isBlank()) {
                showError("模板名称不能为空")
                return@ifPresent
            }

            try {
                val (bindingType, bindingIds) = workbench.getSelectedBindings()
                val evaluatorNode = TreeModelConverter.fromTreeItem(rootNode) { EvaluatorPayload.Rule("") }
                val config = EvaluatorTreeConfig(
                    bindingType = bindingType ?: EvaluatorTreeBindingType.GROUP,
                    bindingIds = bindingIds.ifEmpty { listOf("TEMPLATE") },
                    root = evaluatorNode,
                    leafConfigs = workbench.leafConfigs.toMap()
                )

                val treeValidator = EvaluatorTreeValidator(leafSourceCatalog, pipelineAssembler)
                val treeReport = treeValidator.validate(config)
                if (!treeReport.ok) {
                    val msg = treeReport.diagnostics.joinToString("\n") { "(${it.code}) ${it.message}" }
                    showError("另存模板被拒绝，检测到参数配置不符合约束契约：\n\n$msg")
                    return@ifPresent
                }

                templateService.saveTemplate(
                    name = name,
                    config = config,
                    description = selectedItem.description,
                    groupId = null
                )

                showInfo("存为模板成功", "已成功另存模板 [$name]")
            } catch (e: Exception) {
                showError("另存模板失败: ${e.message}")
            }
        }
    }
}

class DeleteTreeAction : TreeWorkbenchAction, KoinComponent {
    override val title: String = "删除"
    override val order: Int = 30

    private val treeConfigService: TreeConfigService by inject()

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
                    treeConfigService.delete(selectedItem.id)
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
 * 从模板新建评估树：选择一个已有模板，引导输入配置属性，载入树结构和叶子配置为新草稿。
 */
class CreateFromTemplateAction : TreeWorkbenchAction, KoinComponent {
    override val title: String = "从模板新建"
    override val order: Int = 12

    private val templateService: EvaluatorTreeTemplateService by inject()

    override fun execute(workbench: EvaluatorTreeWorkbench) {
        val templates = templateService.loadAllTemplates()
        if (templates.isEmpty()) {
            showError("暂无可用模板，请先使用 [存为模板] 功能创建模板")
            return
        }

        // 1. 弹出选择模板对话框
        val choices = templates.associateBy { it.first.name }
        val choiceDialog = javafx.scene.control.ChoiceDialog(
            choices.keys.first(),
            choices.keys
        ).apply {
            title = "从模板新建"
            headerText = "选择一个模板作为基础创建新评估树"
            contentText = "模板:"
        }

        choiceDialog.showAndWait().ifPresent { templateName ->
            val templatePair = choices[templateName] ?: return@ifPresent
            val (entity, config) = templatePair
            if (config == null) {
                showError("模板解析失败")
                return@ifPresent
            }

            // 2. 弹出属性对话框，让用户明确指定新策略的名称、描述与应用目标
            val propDialog = TreePropertiesDialog(
                initialName = "${entity.name} 策略",
                initialDescription = entity.description,
                initialEnabled = true,
                initialBindingType = EvaluatorTreeBindingType.GROUP,
                initialBindingIds = emptyList(),
                initialManagerId = null
            )

            propDialog.showAndWait().ifPresent { result ->
                if (result.name.isBlank()) {
                    showError("名称不能为空")
                    return@ifPresent
                }
                if (result.bindingIds.isEmpty()) {
                    showError("必须选择至少一个绑定目标")
                    return@ifPresent
                }

                // 3. 创建新草稿并装载模板的树结构与完整的叶子配置
                val draftItem = workbench.addDraftItem(
                    result.name, result.description, result.enabled, result.bindingType, result.bindingIds,
                    managerId = result.managerId,
                    initialRoot = config.root, initialLeafConfigs = config.leafConfigs
                )

                // 4. 选中草稿（自动刷新工作台面板并展示模板的树与叶子配置）
                workbench.configListView.selectionModel.select(draftItem)

                // 自动选中根节点，驱动右侧属性面板展示数据
                workbench.nodeTreeView.root?.let {
                    workbench.nodeTreeView.selectionModel.select(it)
                }
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
