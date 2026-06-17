package lin.condition_tree.ui.components

import javafx.geometry.Insets
import javafx.scene.control.*
import javafx.scene.layout.*
import lin.rule.condition.ConditionPayload
import lin.rule.condition.ConditionRegistry
import lin.rule.condition.orthogonal.DataSource
import lin.rule.condition.orthogonal.Operator
import lin.tree_config.ui.DynamicFieldForm
import lin.ui.service.createTreeConfigMapper
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.*

class OrthogonalConditionDialog(
    private val initialPayload: ConditionPayload.OrthogonalRef? = null
) : Dialog<ConditionPayload.OrthogonalRef>(), KoinComponent {

    private val conditionRegistry: ConditionRegistry by inject()
    private val templateRepo: lin.orthogonal_template.db.OrthogonalTemplateRepository by inject()

    private val dynamicFieldForm by lazy { DynamicFieldForm() }

    init {
        title = "配置正交条件"
        headerText = "正交条件由 数据源 + 比较算子 组合构成，并提供算子所需的具体参数"

        val dialogPane = this.dialogPane
        dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)

        val assembler = conditionRegistry.conditionAssembler
            ?: error("ConditionAssembler is not configured in this context")

        val dataSources = assembler.allDataSources().toList()
        val allOperators = assembler.allOperators().toList()
        val mapper = createTreeConfigMapper()

        // 1. 数据源下拉框
        val dataSourceCombo = ComboBox<DataSource<*>>().apply {
            maxWidth = Double.MAX_VALUE
            items.addAll(dataSources)
            setCellFactory { createDataSourceCell() }
            buttonCell = createDataSourceCell()
        }

        // 2. 算子下拉框
        val operatorCombo = ComboBox<Operator<*, *>>().apply {
            maxWidth = Double.MAX_VALUE
            setCellFactory { createOperatorCell() }
            buttonCell = createOperatorCell()
            isDisable = true
        }

        // 3. 参数区及表单
        val paramsContainer = VBox(6.0)
        val argsMap = mutableMapOf<String, Any>()

        // 载入 Payload 辅助函数
        fun loadPayload(ref: ConditionPayload.OrthogonalRef) {
            val ds = dataSources.firstOrNull { it.id == ref.sourceId }
            if (ds != null) {
                dataSourceCombo.selectionModel.select(ds)

                val compatibleOps = allOperators.filter { isCompatible(it.inputType, ds.outputType) }
                operatorCombo.items.setAll(compatibleOps)
                operatorCombo.isDisable = false

                val op = compatibleOps.firstOrNull { it.id == ref.operatorId }
                if (op != null) {
                    operatorCombo.selectionModel.select(op)
                    argsMap.clear()
                    argsMap.putAll(ref.args)
                    rebuildForm(paramsContainer, ds, op, argsMap)
                }
            }
        }

        // 加载初始值
        initialPayload?.let { loadPayload(it) }

        // 数据源改变监听
        dataSourceCombo.selectionModel.selectedItemProperty().addListener { _, _, newDs ->
            operatorCombo.items.clear()
            paramsContainer.children.clear()
            argsMap.clear()

            if (newDs != null) {
                val compatibleOps = allOperators.filter { isCompatible(it.inputType, newDs.outputType) }
                operatorCombo.items.setAll(compatibleOps)
                operatorCombo.isDisable = false
            } else {
                operatorCombo.isDisable = true
            }
        }

        // 算子改变监听
        operatorCombo.selectionModel.selectedItemProperty().addListener { _, _, newOp ->
            paramsContainer.children.clear()
            argsMap.clear()
            if (newOp != null) {
                rebuildForm(paramsContainer, dataSourceCombo.value, newOp, argsMap)
            }
        }


        // 模板选择与保存区域
        val templateCombo = ComboBox<lin.orthogonal_template.db.OrthogonalTemplateEntity>().apply {
            maxWidth = Double.MAX_VALUE
            promptText = "应用现有条件模板..."
        }

        fun reloadTemplates() {
            val templates = templateRepo.findAllByType("CONDITION")
            templateCombo.items.setAll(templates)
        }
        reloadTemplates()

        templateCombo.setCellFactory {
            object : ListCell<lin.orthogonal_template.db.OrthogonalTemplateEntity>() {
                override fun updateItem(item: lin.orthogonal_template.db.OrthogonalTemplateEntity?, empty: Boolean) {
                    super.updateItem(item, empty)
                    text = if (empty || item == null) null else "${item.name} (${item.description ?: "无描述"})"
                }
            }
        }
        templateCombo.buttonCell = templateCombo.cellFactory.call(null)

        templateCombo.selectionModel.selectedItemProperty().addListener { _, _, template ->
            if (template != null) {
                try {
                    val ref = mapper.readValue(template.contentJson, ConditionPayload.OrthogonalRef::class.java)
                    loadPayload(ref)
                } catch (e: Exception) {
                    e.printStackTrace()
                    Alert(Alert.AlertType.ERROR, "加载模板失败: ${e.message}").showAndWait()
                }
            }
        }

        val saveTemplateBtn = Button("保存为模板...").apply {
            setOnAction {
                val ds = dataSourceCombo.value
                val op = operatorCombo.value
                if (ds == null || op == null) {
                    Alert(Alert.AlertType.WARNING, "请先配置完有效的数据源与比较算子再保存模板！").showAndWait()
                    return@setOnAction
                }

                val nameDialog = Dialog<Pair<String, String>>().apply {
                    title = "保存为正交条件模板"
                    headerText = "请输入模板的名称和描述"
                    val dialogPane = this.dialogPane
                    dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)

                    val nameInput = TextField().apply { promptText = "模板名称 (例如: 己方手牌数量大于等于3)" }
                    val descInput = TextField().apply { promptText = "描述信息 (例如: 适用于快速铺场卡组)" }
                    dialogPane.content = VBox(8.0).apply {
                        children.addAll(
                            Label("模板名称:"), nameInput,
                            Label("描述:"), descInput
                        )
                    }
                    setResultConverter { buttonType ->
                        if (buttonType == ButtonType.OK) {
                            nameInput.text.trim() to descInput.text.trim()
                        } else null
                    }
                }

                val res = nameDialog.showAndWait()
                if (res.isPresent) {
                    val (name, desc) = res.get() as Pair<String, String>
                    if (name.isBlank()) {
                        Alert(Alert.AlertType.WARNING, "模板名称不能为空！").showAndWait()
                        return@setOnAction
                    }

                    val ref = ConditionPayload.OrthogonalRef(
                        sourceId = ds.id,
                        operatorId = op.id,
                        args = HashMap(argsMap)
                    )

                    val entity = lin.orthogonal_template.db.OrthogonalTemplateEntity(
                        id = "",
                        name = name,
                        description = desc.takeIf { it.isNotBlank() },
                        type = "CONDITION",
                        contentJson = mapper.writeValueAsString(ref)
                    )

                    try {
                        templateRepo.save(entity)
                        Alert(Alert.AlertType.INFORMATION, "模板保存成功！").showAndWait()
                        reloadTemplates()
                    } catch (e: Exception) {
                        e.printStackTrace()
                        Alert(Alert.AlertType.ERROR, "保存模板失败: ${e.message}").showAndWait()
                    }
                }
            }
        }

        val grid = GridPane().apply {
            hgap = 10.0
            vgap = 10.0
            padding = Insets(20.0, 50.0, 10.0, 10.0)
            columnConstraints.addAll(
                ColumnConstraints().apply { hgrow = Priority.NEVER },
                ColumnConstraints().apply { hgrow = Priority.ALWAYS }
            )
        }

        grid.add(Label("应用模板:"), 0, 0)
        grid.add(HBox(8.0).apply {
            children.addAll(templateCombo, saveTemplateBtn)
            HBox.setHgrow(templateCombo, Priority.ALWAYS)
        }, 1, 0)

        grid.add(Label("条件数据源:"), 0, 1)
        grid.add(dataSourceCombo, 1, 1)

        grid.add(Label("比较算子:"), 0, 2)
        grid.add(operatorCombo, 1, 2)

        grid.add(Label("算子参数:"), 0, 3)
        grid.add(paramsContainer, 1, 3)

        dialogPane.content = grid

        // 校验输入
        val okButton = dialogPane.lookupButton(ButtonType.OK) as Button
        okButton.addEventFilter(javafx.event.ActionEvent.ACTION) { event ->
            val ds = dataSourceCombo.value
            val op = operatorCombo.value
            if (ds == null || op == null) {
                val alert = Alert(Alert.AlertType.WARNING, "必须选择数据源和算子！")
                alert.showAndWait()
                event.consume()
            }
        }

        setResultConverter { buttonType ->
            if (buttonType == ButtonType.OK) {
                val ds = dataSourceCombo.value!!
                val op = operatorCombo.value!!
                ConditionPayload.OrthogonalRef(
                    sourceId = ds.id,
                    operatorId = op.id,
                    refId = "${ds.id}_${op.id}_${UUID.randomUUID().toString().substring(0, 4)}",
                    args = HashMap(argsMap)
                )
            } else {
                null
            }
        }
    }

    private fun rebuildForm(
        container: VBox,
        dataSource: DataSource<*>?,
        operator: Operator<*, *>?,
        args: MutableMap<String, Any>
    ) {
        container.children.clear()
        if (operator == null) return

        val specs = (dataSource?.fields ?: emptyList()) + operator.paramSpecs
        if (specs.isEmpty()) {
            container.children.add(Label("无需配置参数。").apply {
                style = "-fx-text-fill: #888; -fx-font-style: italic;"
            })
            return
        }

        val form = dynamicFieldForm.build(
            specs = specs,
            existingValues = { propertyName -> args[propertyName] }
        ) { propertyName, value ->
            args[propertyName] = value
        }
        container.children.add(form)
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

    private fun createOperatorCell(): ListCell<Operator<*, *>> {
        return object : ListCell<Operator<*, *>>() {
            override fun updateItem(item: Operator<*, *>?, empty: Boolean) {
                super.updateItem(item, empty)
                text = if (empty || item == null) null
                else "${item.name} (${item.id})"
            }
        }
    }

    private fun isCompatible(inputType: kotlin.reflect.KClass<*>, outputType: kotlin.reflect.KClass<*>): Boolean {
        if (inputType == outputType) return true
        if (inputType == Number::class && Number::class.java.isAssignableFrom(outputType.javaObjectType)) return true
        return false
    }
}
