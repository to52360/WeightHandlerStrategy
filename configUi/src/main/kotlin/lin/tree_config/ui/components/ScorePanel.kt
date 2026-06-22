package lin.tree_config.ui.components

import javafx.geometry.Insets
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.TextField
import javafx.scene.layout.ColumnConstraints
import javafx.scene.layout.GridPane
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import lin.rule.orthogonal.DataSource
import lin.rule.orthogonal.Transform
import lin.rule.score.ScoreOperator
import lin.tree_config.ui.DynamicFieldForm
import kotlin.reflect.KType
import kotlin.reflect.full.isSubtypeOf

/**
 * 正交规则的评分面板 — 仅支持数据源评分 (SourceScore)。
 * 固定分 (ConstantScore) 不属于正交规则的职责范围，已于从 ScorePanel 中移除。
 */
class ScorePanel(
    dataSources: List<DataSource<*>>,
    allTransforms: List<Transform<*, *>>,
    scoreOperators: List<ScoreOperator<*, *>>,
    dynamicFieldForm: DynamicFieldForm
) {
    val root = VBox(10.0).apply {
        padding = Insets(10.0)
        style = "-fx-border-color: #ddd; -fx-border-radius: 4px; -fx-background-color: #fafafa;"
    }

    val sourceDsCombo = ComboBox<DataSource<*>>().apply {
        items.addAll(dataSources)
        maxWidth = Double.MAX_VALUE
        setCellFactory { createDataSourceCell() }
        buttonCell = createDataSourceCell()
    }
    val sourceOpCombo = ComboBox<ScoreOperator<*, *>>().apply {
        maxWidth = Double.MAX_VALUE
        setCellFactory { createScoreOperatorCell() }
        buttonCell = createScoreOperatorCell()
        isDisable = true
    }
    val sourceOpArgsContainer = VBox(5.0)
    val sourceMissField = TextField("0.0")

    val scorePipelineEditor = OrthogonalPipelineEditor(
        allTransforms = allTransforms,
        dynamicFieldForm = dynamicFieldForm
    ) { finalType ->
        if (finalType != null) {
            val compatibleOps = scoreOperators.filter { isCompatible(it.inputType, finalType) }
            val selectedOp = sourceOpCombo.value
            sourceOpCombo.items.setAll(compatibleOps)
            sourceOpCombo.isDisable = false
            if (selectedOp != null && compatibleOps.any { it.id == selectedOp.id }) {
                sourceOpCombo.selectionModel.select(selectedOp)
            } else {
                sourceOpCombo.selectionModel.clearSelection()
            }
        } else {
            sourceOpCombo.items.clear()
            sourceOpCombo.isDisable = true
        }
    }

    private val sourceGrid = GridPane().apply {
        hgap = 8.0; vgap = 8.0
        columnConstraints.addAll(
            ColumnConstraints().apply {
                hgrow = Priority.NEVER
                prefWidth = 100.0
                minWidth = 100.0
            },
            ColumnConstraints().apply { hgrow = Priority.ALWAYS }
        )
        add(Label("评分数据源:"), 0, 0)
        add(sourceDsCombo, 1, 0)
        add(Label("转换器链:"), 0, 1)
        add(scorePipelineEditor, 1, 1)
        add(Label("评分算子:"), 0, 2)
        add(sourceOpCombo, 1, 2)
        add(Label("算子参数:"), 0, 3)
        add(sourceOpArgsContainer, 1, 3)
        add(Label("未命中分数:"), 0, 4)
        add(sourceMissField, 1, 4)
    }

    init {
        root.children.add(Label("评分效应 (SourceScore)").apply {
            style = "-fx-font-weight: bold; -fx-font-size: 14px;"
        })
        root.children.add(sourceGrid)
    }

    private fun createDataSourceCell(): ListCell<DataSource<*>> {
        return object : ListCell<DataSource<*>>() {
            override fun updateItem(item: DataSource<*>?, empty: Boolean) {
                super.updateItem(item, empty)
                text = if (empty || item == null) null
                else "${item.name} (${item.id})"
            }
        }
    }

    private fun createScoreOperatorCell(): ListCell<ScoreOperator<*, *>> {
        return object : ListCell<ScoreOperator<*, *>>() {
            override fun updateItem(item: ScoreOperator<*, *>?, empty: Boolean) {
                super.updateItem(item, empty)
                text = if (empty || item == null) null
                else "${item.name} (${item.id})"
            }
        }
    }

    private fun isCompatible(inputType: KType, outputType: KType): Boolean {
        return inputType == outputType || outputType.isSubtypeOf(inputType)
    }
}
