package lin.ui.tree_config.components

import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import lin.rule.condition.ConditionMeta
import lin.ui.tree_config.DynamicFieldForm

class GuardPanel(
    allConditions: List<ConditionMeta>,
    allConditionTrees: List<Pair<String, String>>,
    private val dynamicFieldForm: DynamicFieldForm
) {
    val root = VBox(10.0).apply {
        padding = Insets(10.0)
        style = "-fx-border-color: #ddd; -fx-border-radius: 4px; -fx-background-color: #fafafa;"
    }

    val typeCombo = ComboBox<String>().apply {
        items.addAll("无 (Always True)", "普通条件", "条件树", "正交条件")
        selectionModel.selectFirst()
        maxWidth = Double.MAX_VALUE
    }

    val detailArea = VBox(8.0)

    // 普通条件关联控件
    val condCombo = ComboBox<ConditionMeta>().apply {
        items.addAll(allConditions)
        maxWidth = Double.MAX_VALUE
        setCellFactory { createConditionMetaCell() }
        buttonCell = createConditionMetaCell()
    }
    val condArgsContainer = VBox(5.0)

    // 条件树关联控件
    val treeCombo = ComboBox<Pair<String, String>>().apply {
        items.addAll(allConditionTrees)
        maxWidth = Double.MAX_VALUE
        setCellFactory { createConditionTreeCell() }
        buttonCell = createConditionTreeCell()
    }

    // 正交条件关联控件
    val configBtn = Button("编辑正交条件...")
    val summaryLabel = Label("未配置正交条件").apply {
        style = "-fx-text-fill: #888; -fx-font-style: italic;"
    }

    init {
        root.children.add(Label("守卫条件 (Guard)").apply {
            style = "-fx-font-weight: bold; -fx-font-size: 14px;"
        })
        root.children.add(HBox(8.0, Label("条件类型:"), typeCombo).apply { alignment = Pos.CENTER_LEFT })
        root.children.add(detailArea)
    }

    fun showType(type: String) {
        detailArea.children.clear()
        when (type) {
            "普通条件" -> detailArea.children.addAll(condCombo, condArgsContainer)
            "条件树" -> detailArea.children.add(treeCombo)
            "正交条件" -> detailArea.children.addAll(configBtn, summaryLabel)
        }
    }

    private fun createConditionMetaCell(): ListCell<ConditionMeta> {
        return object : ListCell<ConditionMeta>() {
            override fun updateItem(item: ConditionMeta?, empty: Boolean) {
                super.updateItem(item, empty)
                text = if (empty || item == null) null
                else "${item.name} (${item.conditionId})"
            }
        }
    }

    private fun createConditionTreeCell(): ListCell<Pair<String, String>> {
        return object : ListCell<Pair<String, String>>() {
            override fun updateItem(item: Pair<String, String>?, empty: Boolean) {
                super.updateItem(item, empty)
                text = if (empty || item == null) null
                else "${item.second} (${item.first})"
            }
        }
    }
}
