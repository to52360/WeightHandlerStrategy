package lin.ui.condition_tree.ui

import javafx.geometry.Insets
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.layout.FlowPane
import javafx.scene.layout.VBox
import lin.ui.components.TreeConfigStrategy
import lin.ui.condition_tree.ui.action.ConditionTreeWorkbenchAction
import lin.ui.tree_config.ui.TreeModelConverter
import org.koin.core.component.KoinComponent

class ConditionTreeConfigListPanel(
    private val workbench: ConditionTreeWorkbench,
    private val treeConfigStrategy: TreeConfigStrategy<lin.rule.condition.ConditionPayload>
) : VBox(5.0), KoinComponent {

    val configListView = ListView<ConditionTreeListItem>()

    init {
        padding = Insets(10.0)
        val title = Label("条件树列表").apply { style = "-fx-font-weight: bold; -fx-padding: 0 0 5 0;" }

        val buttonBox = FlowPane(5.0, 5.0)
        val actions = getKoin().getAll<ConditionTreeWorkbenchAction>().sortedBy { it.order }
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
            object : ListCell<ConditionTreeListItem>() {
                override fun updateItem(item: ConditionTreeListItem?, empty: Boolean) {
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
                if (!item.isDraft) {
                    val loaded = treeConfigStrategy.loadAll().firstOrNull { it.id == item.id }
                    loaded?.root?.let { rootNode ->
                        workbench.nodeTreeView.root =
                            TreeModelConverter.toTreeItem(rootNode)
                    }
                }
            }
        }
    }

    fun refreshList() {
        val currentDrafts = configListView.items.filter { it.isDraft }
        configListView.items.clear()
        val configs = treeConfigStrategy.loadAll()
        configs.forEach { loaded ->
            configListView.items.add(ConditionTreeListItem(loaded.id, loaded.name))
        }
        currentDrafts.forEach { configListView.items.add(0, it) }
    }

    fun addDraftItem(name: String): ConditionTreeListItem {
        val draftItem = ConditionTreeListItem(
            id = "draft_${System.currentTimeMillis()}",
            name = name,
            isDraft = true
        )
        configListView.items.add(0, draftItem)
        return draftItem
    }
}
