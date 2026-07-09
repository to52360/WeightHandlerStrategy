package lin.ui.tree_config.ui.components

import javafx.geometry.Insets
import javafx.scene.control.Button
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.layout.*
import lin.db.OrthogonalTemplateEntity
import lin.rule.orthogonal.DataSource
import lin.rule.orthogonal.Operator
import lin.rule.orthogonal.Transform
import lin.ui.tree_config.ui.DynamicFieldForm

class ConditionConfigPanel(
    dataSources: List<DataSource<*>>,
    allTransforms: List<Transform<*, *>>,
    dynamicFieldForm: DynamicFieldForm,
    onFinalTypeChanged: (kotlin.reflect.KType?) -> Unit
) {
    val root = HBox(15.0).apply {
        padding = Insets(10.0)
        prefWidth = 850.0
        prefHeight = 480.0
    }

    // 左侧主要配置面板（数据源、算子及参数）
    val leftVBox = VBox(10.0).apply {
        padding = Insets(10.0)
        style = "-fx-border-color: #ddd; -fx-border-radius: 4px; -fx-background-color: #fafafa;"
        prefWidth = 380.0
        minWidth = 380.0
        maxWidth = 380.0
    }

    val templateCombo = ComboBox<OrthogonalTemplateEntity>().apply {
        maxWidth = Double.MAX_VALUE
        promptText = "应用条件模板..."
    }
    val saveTemplateBtn = Button("保存为模板")

    val dataSourceCombo = ComboBox<DataSource<*>>().apply {
        maxWidth = Double.MAX_VALUE
    }

    val operatorCombo = ComboBox<Operator<*, *>>().apply {
        maxWidth = Double.MAX_VALUE
        isDisable = true
    }

    val paramsContainer = VBox(6.0)

    private val grid = GridPane().apply {
        hgap = 10.0
        vgap = 10.0
        columnConstraints.addAll(
            ColumnConstraints().apply {
                hgrow = Priority.NEVER
                prefWidth = 100.0
                minWidth = 100.0
            },
            ColumnConstraints().apply { hgrow = Priority.ALWAYS }
        )
        add(Label("应用模板:"), 0, 0)
        add(HBox(8.0, templateCombo, saveTemplateBtn).apply { HBox.setHgrow(templateCombo, Priority.ALWAYS) }, 1, 0)
        add(Label("条件数据源:"), 0, 1)
        add(dataSourceCombo, 1, 1)
        add(Label("比较算子:"), 0, 2)
        add(operatorCombo, 1, 2)
        add(Label("算子参数:"), 0, 3)
        add(paramsContainer, 1, 3)
    }

    // 右侧转换器管道链面板
    val rightVBox = VBox(10.0).apply {
        padding = Insets(10.0)
        style = "-fx-border-color: #ddd; -fx-border-radius: 4px; -fx-background-color: #fafafa;"
        HBox.setHgrow(this, Priority.ALWAYS)
    }

    val pipelineEditor = OrthogonalPipelineEditor(
        allTransforms = allTransforms,
        dynamicFieldForm = dynamicFieldForm,
        onFinalTypeChanged = onFinalTypeChanged
    )

    init {
        // 装填数据
        dataSourceCombo.items.addAll(dataSources)

        leftVBox.children.addAll(
            Label("核心参数配置").apply { style = "-fx-font-weight: bold; -fx-font-size: 14px;" },
            grid
        )

        rightVBox.children.addAll(
            Label("数据转换链 (Transforms)").apply { style = "-fx-font-weight: bold; -fx-font-size: 14px;" },
            pipelineEditor
        )

        root.children.addAll(leftVBox, rightVBox)
    }
}
