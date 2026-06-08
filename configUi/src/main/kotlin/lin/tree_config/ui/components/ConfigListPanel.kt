package lin.tree_config.ui.components

import javafx.geometry.Insets
import javafx.scene.control.*
import javafx.scene.layout.FlowPane
import javafx.scene.layout.VBox
import lin.card_purpose.PurposeTagProvider
import lin.rule.tree.EvaluatorTreeBindingType
import lin.tree_config.ui.ConfigListItem
import lin.tree_config.ui.EvaluatorTreeWorkbench
import lin.tree_config.ui.action.TreeWorkbenchAction
import lin.ui.components.PaginationBar
import lin.ui.service.TreeConfigService
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class ConfigListPanel(
    private val workbench: EvaluatorTreeWorkbench,
    private val treeConfigService: TreeConfigService
) : VBox(5.0), KoinComponent {

    private val tagProvider: PurposeTagProvider by inject()

    val configListView = ListView<ConfigListItem>()
    private val filterComboBox = ComboBox<String>().apply {
        items.addAll("全部", "按卡组绑定", "按用途标签绑定")
        selectionModel.selectFirst()
        maxWidth = Double.MAX_VALUE
    }

    private val paginationBar = PaginationBar { page ->
        refreshList(page)
    }

    init {
        padding = Insets(10.0)
        val title = Label("评估树列表").apply { style = "-fx-font-weight: bold; -fx-padding: 0 0 5 0;" }

        val buttonBox = FlowPane(5.0, 5.0)
        val actions = getKoin().getAll<TreeWorkbenchAction>().sortedBy { it.order }
        for (action in actions) {
            val btn = Button(action.title).apply {
                setOnAction { action.execute(workbench) }
            }
            buttonBox.children.add(btn)
        }

        filterComboBox.setOnAction {
            paginationBar.reset()
            refreshList()
        }

        children.addAll(title, filterComboBox, buttonBox, configListView, paginationBar)
        setVgrow(configListView, javafx.scene.layout.Priority.ALWAYS)

        setupListView()
    }

    private fun setupListView() {
        configListView.setCellFactory {
            object : ListCell<ConfigListItem>() {
                init {
                    setOnMouseClicked { event ->
                        if (event.clickCount == 2 && !isEmpty && item != null) {
                            val action = getKoin().getAll<TreeWorkbenchAction>()
                                .find { it.title == "属性" || it.title == "修改属性" || it.title == "修改" }
                            action?.execute(workbench)
                        }
                    }
                }
                
                override fun updateItem(item: ConfigListItem?, empty: Boolean) {
                    super.updateItem(item, empty)
                    if (empty || item == null) {
                        text = null
                        style = ""
                    } else {
                        val statusStr = if (item.enabled) "" else " [已禁用]"
                        val templateStr = if (item.isTemplate) " [模板]" else ""
                        text = when {
                            item.isDraft -> "* ${item.name}$statusStr (未保存)"
                            else -> "${item.name}$templateStr$statusStr"
                        }
                        style = when {
                            !item.enabled -> "-fx-text-fill: #999999;"
                            item.isDraft -> "-fx-text-fill: gray; -fx-font-style: italic;"
                            item.isTemplate -> "-fx-text-fill: #2196F3;"
                            else -> ""
                        }
                    }
                }
            }
        }

        configListView.selectionModel.selectedItemProperty().addListener { _, _, newValue ->
            newValue?.let { item ->
                workbench.setSelectedBindings(item.config?.bindings ?: emptyList())
                workbench.setCurrentEnabled(item.enabled)
                if (item.config != null) {
                    workbench.logicTreeEditor.treeView.root =
                        lin.tree_config.ui.TreeModelConverter.toTreeItem(item.config.root)
                    workbench.leafConfigs.clear()
                    workbench.leafConfigs.putAll(item.config.leafConfigs)
                }
            }
        }
    }

    fun refreshList(page: Int = paginationBar.currentPage) {
        val currentDrafts = configListView.items.filter { it.isDraft }
        configListView.items.clear()

        val activeManagerId = workbench.activeManagerHolder?.activeManagerId

        // 加载当前 manager + 全局共享的配置（含模板或仅非模板取决于 isTemplate 过滤）
        val configs = treeConfigService.loadByManagerId(activeManagerId)

        val filterType = filterComboBox.selectionModel.selectedItem ?: "全部"
        val bindingTypeStr = when (filterType) {
            "按卡组绑定" -> EvaluatorTreeBindingType.GROUP.name
            "按用途标签绑定" -> EvaluatorTreeBindingType.PURPOSE_TAG.name
            else -> null
        }

        // 简化分页：当用 manager 过滤时直接全量展示（数据量不大）
        val totalConfigs = configs.size
        paginationBar.update(totalConfigs, page)

        val offset = (paginationBar.currentPage - 1) * paginationBar.pageSize
        val pagedConfigs = configs.drop(offset).take(paginationBar.pageSize)

        pagedConfigs.filter { entity ->
            bindingTypeStr == null || entity.first.bindingType == bindingTypeStr
        }.forEach { (entity, config) ->
            configListView.items.add(
                ConfigListItem(
                    entity.id,
                    entity.name,
                    entity.bindingsSummary,
                    config,
                    enabled = entity.enabled,
                    managerId = entity.managerId,
                    isTemplate = entity.isTemplate
                )
            )
        }
        currentDrafts.filter { draft ->
            val firstBindingType = draft.config?.bindings?.firstOrNull()?.type
            when (filterType) {
                "按卡组绑定" -> firstBindingType == EvaluatorTreeBindingType.GROUP
                "按用途标签绑定" -> firstBindingType == EvaluatorTreeBindingType.PURPOSE_TAG
                else -> true
            }
        }.forEach { configListView.items.add(0, it) }
    }

    fun addDraftItem(
        name: String,
        enabled: Boolean = true,
        bindings: List<lin.rule.tree.EvaluatorTreeBinding> = emptyList(),
        managerId: String? = null,
        isTemplate: Boolean = false
    ): ConfigListItem {
        val draftItem = ConfigListItem(
            id = "draft_${System.currentTimeMillis()}",
            name = name,
            bindingsSummary = bindings.joinToString(",") { binding ->
                when (binding.type) {
                    EvaluatorTreeBindingType.PURPOSE_TAG -> tagProvider.displayName(binding.id)
                    else -> binding.id
                }
            },
            config = lin.rule.tree.EvaluatorTreeConfig(
                bindings = bindings,
                root = lin.rule.tree.LogicNode.And(emptyList()),
                leafConfigs = emptyMap()
            ),
            isDraft = true,
            enabled = enabled,
            managerId = managerId,
            isTemplate = isTemplate
        )
        configListView.items.add(0, draftItem)
        return draftItem
    }
}
