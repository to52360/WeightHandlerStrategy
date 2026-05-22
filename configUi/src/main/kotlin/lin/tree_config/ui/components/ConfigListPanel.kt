package lin.tree_config.ui.components

import javafx.geometry.Insets
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.layout.FlowPane
import javafx.scene.layout.VBox
import lin.tree_config.service.TreeConfigService
import lin.tree_config.ui.ConfigListItem
import lin.tree_config.ui.EvaluatorTreeWorkbench
import lin.tree_config.ui.action.TreeWorkbenchAction
import org.koin.core.component.KoinComponent

class ConfigListPanel(
    private val workbench: EvaluatorTreeWorkbench,
    private val treeConfigService: TreeConfigService
) : VBox(5.0), KoinComponent {

    val configListView = ListView<ConfigListItem>()

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

        children.addAll(title, buttonBox, configListView)

        setupListView()
    }

    private fun setupListView() {
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
                workbench.setSelectedGroupIds(item.groupIds)
                if (item.config != null) {
                    workbench.logicTreeEditor.treeView.root =
                        lin.tree_config.ui.TreeModelConverter.toTreeItem(item.config.root)
                    workbench.leafConfigs.clear()
                    workbench.leafConfigs.putAll(item.config.leafConfigs)
                }
            }
        }
    }

    fun refreshList() {
        val currentDrafts = configListView.items.filter { it.isDraft }
        configListView.items.clear()
        val configs = treeConfigService.loadAll()
        configs.forEach { (entity, config) ->
            val ids = entity.groupIds.split(",").filter { it.isNotBlank() }
            configListView.items.add(ConfigListItem(entity.id, entity.name, ids, config))
        }
        currentDrafts.forEach { configListView.items.add(0, it) }
    }

    fun addDraftItem(name: String): ConfigListItem {
        val draftItem = ConfigListItem(
            id = "draft_${System.currentTimeMillis()}",
            name = name,
            groupIds = emptyList(),
            config = null,
            isDraft = true
        )
        configListView.items.add(0, draftItem)
        return draftItem
    }
}
