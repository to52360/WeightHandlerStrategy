package lin.ui.condition_tree.components

import javafx.scene.control.*
import javafx.scene.layout.VBox
import lin.repository.OrthogonalTemplateEntity
import lin.repository.OrthogonalTemplateRepository
import lin.repository.TemplateGroupEntity
import lin.repository.TemplateGroupRepository
import lin.rule.condition.ConditionPayload
import lin.rule.condition.ConditionRegistry
import lin.rule.orthogonal.DataSource
import lin.rule.orthogonal.Operator
import lin.rule.orthogonal.Transform
import lin.ui.components.TemplateNameDialog
import lin.ui.service.createTreeConfigMapper
import lin.ui.tree_config.DynamicFieldForm
import lin.ui.tree_config.components.ConditionConfigPanel
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.*
import kotlin.reflect.KType
import kotlin.reflect.full.isSubtypeOf

class OrthogonalConditionDialog(
    private val initialPayload: ConditionPayload.PipelineRef? = null
) : Dialog<ConditionPayload.PipelineRef>(), KoinComponent {

    private val conditionRegistry: ConditionRegistry by inject()
    private val templateRepo: OrthogonalTemplateRepository by inject()
    private val templateGroupRepo: TemplateGroupRepository by inject()

    private val dynamicFieldForm by lazy { DynamicFieldForm() }

    private class ConditionDataContext(
        val dataSources: List<DataSource<*>>,
        val allOperators: List<Operator<*, *>>,
        val allTransforms: List<Transform<*, *>>,
        val mapper: com.fasterxml.jackson.databind.ObjectMapper
    )

    private class ConditionInteractionState(
        val argsMap: MutableMap<String, Any> = mutableMapOf()
    )

    init {
        title = "配置正交条件"
        headerText = "正交条件由 数据源 + 比较算子 组合构成，并提供算子所需的具体参数"

        val dialogPane = this.dialogPane
        dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)

        val assembler = conditionRegistry.pipelineAssembler
            ?: error("PipelineAssembler is not configured in this context")

        // 1. 数据与状态封装
        val dataContext = ConditionDataContext(
            dataSources = assembler.allDataSources().toList(),
            allOperators = assembler.allOperators().toList(),
            allTransforms = assembler.allTransforms().toList(),
            mapper = createTreeConfigMapper()
        )

        val uiState = ConditionInteractionState()

        // 2. 初始化布局面板
        lateinit var panel: ConditionConfigPanel
        panel = ConditionConfigPanel(
            dataContext.dataSources,
            dataContext.allTransforms,
            dynamicFieldForm
        ) { finalType ->
            if (finalType != null) {
                val compatibleOps = dataContext.allOperators.filter { isCompatible(it.inputType, finalType) }
                val selectedOp = panel.operatorCombo.value
                panel.operatorCombo.items.setAll(compatibleOps)
                panel.operatorCombo.isDisable = false
                if (selectedOp != null && compatibleOps.any { it.id == selectedOp.id }) {
                    panel.operatorCombo.selectionModel.select(selectedOp)
                } else {
                    panel.operatorCombo.selectionModel.clearSelection()
                    panel.paramsContainer.children.clear()
                    uiState.argsMap.clear()
                }
            } else {
                panel.operatorCombo.items.clear()
                panel.operatorCombo.isDisable = true
                panel.paramsContainer.children.clear()
                uiState.argsMap.clear()
            }
        }

        // 3. 配置 ComboBox 的 cell factory (样式绑定)
        panel.dataSourceCombo.setCellFactory { createDataSourceCell() }
        panel.dataSourceCombo.buttonCell = createDataSourceCell()
        panel.operatorCombo.setCellFactory { createOperatorCell() }
        panel.operatorCombo.buttonCell = createOperatorCell()

        // 4. 加载 Payload 辅助函数
        fun loadPayload(ref: ConditionPayload.PipelineRef) {
            val ds = dataContext.dataSources.firstOrNull { it.id == ref.sourceId }
            if (ds != null) {
                panel.dataSourceCombo.selectionModel.select(ds)
                panel.pipelineEditor.loadTransforms(ref.transforms)
                panel.crossCardCheckBox.isSelected = ref.crossCard

                val op = dataContext.allOperators.firstOrNull { it.id == ref.operatorId }
                if (op != null) {
                    panel.operatorCombo.selectionModel.select(op)
                    uiState.argsMap.clear()
                    uiState.argsMap.putAll(ref.operatorArgs)
                    rebuildForm(panel.paramsContainer, op, uiState.argsMap)
                }
            }
        }

        // 数据源改变监听 (必须在初始加载前注册，否则 pipelineEditor.dataSourceType 不会被初始化)
        panel.dataSourceCombo.selectionModel.selectedItemProperty().addListener { _, _, newDs ->
            panel.operatorCombo.items.clear()
            panel.paramsContainer.children.clear()
            uiState.argsMap.clear()
            panel.pipelineEditor.onDataSourceChanged(newDs?.outputType)
        }

        // 算子改变监听
        panel.operatorCombo.selectionModel.selectedItemProperty().addListener { _, _, newOp ->
            panel.paramsContainer.children.clear()
            uiState.argsMap.clear()
            if (newOp != null) {
                rebuildForm(panel.paramsContainer, newOp, uiState.argsMap)
            }
        }

        // 初始加载 (必须在监听器注册之后)
        initialPayload?.let { loadPayload(it) }

        // 分组过滤
        fun groupEntityCell(item: TemplateGroupEntity?) = item?.name ?: ""
        val groupFilterCombo = ComboBox<TemplateGroupEntity>().apply {
            promptText = "全部分组"
            maxWidth = Double.MAX_VALUE
            setCellFactory {
                object : ListCell<TemplateGroupEntity>() {
                    override fun updateItem(item: TemplateGroupEntity?, empty: Boolean) {
                        super.updateItem(item, empty); text = groupEntityCell(item)
                    }
                }
            }
            buttonCell = object : ListCell<TemplateGroupEntity>() {
                override fun updateItem(item: TemplateGroupEntity?, empty: Boolean) {
                    super.updateItem(item, empty); text = groupEntityCell(item)
                }
            }
        }
        val groupFilterNone = TemplateGroupEntity("", "全部", null)
        groupFilterCombo.items.addAll(listOf(groupFilterNone) + templateGroupRepo.findAll())

        // 模板载入逻辑
        fun reloadTemplates() {
            val selectedGroupId = groupFilterCombo.value?.id
            val allTemplates = templateRepo.findAllByType("CONDITION")
            panel.templateCombo.items.setAll(
                if (selectedGroupId.isNullOrBlank()) allTemplates
                else allTemplates.filter { it.groupId == selectedGroupId }
            )
        }
        reloadTemplates()

        groupFilterCombo.selectionModel.selectedItemProperty().addListener { _, _, _ -> reloadTemplates() }

        TemplateNameDialog.applyTemplateEntityCellFactory(panel.templateCombo)

        panel.templateCombo.selectionModel.selectedItemProperty().addListener { _, _, template ->
            if (template != null) {
                try {
                    val ref =
                        dataContext.mapper.readValue(template.contentJson, ConditionPayload.PipelineRef::class.java)
                    loadPayload(ref)
                } catch (e: Exception) {
                    e.printStackTrace()
                    Alert(Alert.AlertType.ERROR, "加载模板失败: ${e.message}").showAndWait()
                }
            }
        }

        // 模板保存逻辑
        panel.saveTemplateBtn.setOnAction {
            val ds = panel.dataSourceCombo.value
            val op = panel.operatorCombo.value
            if (ds == null || op == null) {
                Alert(Alert.AlertType.WARNING, "请先配置完有效的数据源与比较算子再保存模板！").showAndWait()
                return@setOnAction
            }

            val nameDialog = TemplateNameDialog(
                title = "保存为正交条件模板",
                headerText = "请输入模板的名称和描述",
                namePromptText = "模板名称 (例如: 己方手牌数量大于等于3)",
                descPromptText = "描述信息 (例如: 适用于快速铺场卡组)"
            )

            val res = nameDialog.showAndWait()
            if (res.isPresent) {
                val (name, desc, groupId) = res.get()
                if (name.isBlank()) {
                    Alert(Alert.AlertType.WARNING, "模板名称不能为空！").showAndWait()
                    return@setOnAction
                }

                val ref = ConditionPayload.PipelineRef(
                    sourceId = ds.id,
                    transforms = panel.pipelineEditor.getTransformCalls(),
                    operatorId = op.id,
                    operatorArgs = HashMap(uiState.argsMap),
                    crossCard = panel.crossCardCheckBox.isSelected,
                    refId = "${ds.id}_${op.id}_${UUID.randomUUID().toString().substring(0, 4)}"
                )

                // T-015: 模板只存结构不含参数 (D-007)
                val stripped = ref.copy(
                    operatorArgs = emptyMap(),
                    transforms = ref.transforms.map { it.copy(args = emptyMap()) },
                    refId = ""
                )

                val entity = OrthogonalTemplateEntity(
                    id = "",
                    name = name,
                    description = desc.takeIf { it.isNotBlank() },
                    groupId = groupId,
                    type = "CONDITION",
                    contentJson = dataContext.mapper.writeValueAsString(stripped)
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

        val filterHBox = javafx.scene.layout.HBox(8.0).apply {
            children.addAll(Label("分组:"), groupFilterCombo)
        }
        dialogPane.content = javafx.scene.layout.VBox(5.0).apply {
            children.addAll(filterHBox, panel.root)
        }

        val okButton = dialogPane.lookupButton(ButtonType.OK) as Button
        okButton.addEventFilter(javafx.event.ActionEvent.ACTION) { event ->
            val ds = panel.dataSourceCombo.value
            val op = panel.operatorCombo.value
            if (ds == null || op == null) {
                val alert = Alert(Alert.AlertType.WARNING, "必须选择数据源和算子！")
                alert.showAndWait()
                event.consume()
            }
        }

        setResultConverter { buttonType ->
            if (buttonType == ButtonType.OK) {
                val ds = panel.dataSourceCombo.value!!
                val op = panel.operatorCombo.value!!
                ConditionPayload.PipelineRef(
                    sourceId = ds.id,
                    transforms = panel.pipelineEditor.getTransformCalls(),
                    operatorId = op.id,
                    operatorArgs = HashMap(uiState.argsMap),
                    crossCard = panel.crossCardCheckBox.isSelected,
                    refId = "${ds.id}_${op.id}_${UUID.randomUUID().toString().substring(0, 4)}"
                )
            } else {
                null
            }
        }
    }

    private fun rebuildForm(
        container: VBox,
        operator: Operator<*, *>?,
        args: MutableMap<String, Any>
    ) {
        container.children.clear()
        if (operator == null) return

        val specs = operator.paramSpecs
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

    private fun isCompatible(inputType: KType, outputType: KType): Boolean {
        return inputType == outputType || outputType.isSubtypeOf(inputType)
    }
}
