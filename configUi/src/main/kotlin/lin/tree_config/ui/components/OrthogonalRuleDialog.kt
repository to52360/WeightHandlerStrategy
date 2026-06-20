package lin.tree_config.ui.components

import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.*
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import lin.condition_tree.db.ConditionTreeConfigService
import lin.condition_tree.ui.components.OrthogonalConditionDialog
import lin.rule.condition.ConditionMeta
import lin.rule.condition.ConditionPayload
import lin.rule.condition.ConditionRegistry
import lin.rule.orthogonal.DataSource
import lin.rule.orthogonal.Transform
import lin.rule.score.ScoreEffect
import lin.rule.score.ScoreOperator
import lin.rule.score.ScoreOperatorRegistry
import lin.rule.tree.EvaluatorLeafConfig
import lin.rule.tree.OrthogonalRuleLeafConfig
import lin.tree_config.ui.DynamicFieldForm
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.*

class OrthogonalRuleDialog(
    private val initialConfig: OrthogonalRuleLeafConfig? = null
) : Dialog<OrthogonalRuleLeafConfig>(), KoinComponent {

    private val conditionRegistry: ConditionRegistry by inject()
    private val scoreOperatorRegistry: ScoreOperatorRegistry by inject()
    private val conditionTreeConfigService: ConditionTreeConfigService by inject()

    private val dynamicFieldForm by lazy { DynamicFieldForm() }

    private val templateRepo: lin.orthogonal_template.db.OrthogonalTemplateRepository by inject()

    private class RuleDataContext(
        val dataSources: List<DataSource<*>>,
        val scoreOperators: List<ScoreOperator<*, *>>,
        val allConditions: List<ConditionMeta>,
        val allConditionTrees: List<Pair<String, String>>,
        val allTransforms: List<Transform<*, *>>,
        val mapper: com.fasterxml.jackson.databind.ObjectMapper
    )

    private class RuleInteractionState(
        var selectedGuardPayload: ConditionPayload?,
        var currentScoreEffect: ScoreEffect?,
        val guardArgsMap: MutableMap<String, Any> = mutableMapOf(),
        val scoreArgsMap: MutableMap<String, Any> = mutableMapOf()
    )

    init {
        title = "配置正交规则"
        headerText = "正交规则由 守卫条件 (Guard) + 评分效应 (ScoreEffect) 两个维度组合，左侧配置守卫，右侧配置算分"

        val dialogPane = this.dialogPane
        dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)

        // ==========================================
        // 1. 数据与状态准备（高内聚封装）
        // ==========================================
        val assembler = conditionRegistry.pipelineAssembler
            ?: error("PipelineAssembler is not configured in this context")

        val dataContext = RuleDataContext(
            dataSources = assembler.allDataSources().toList(),
            scoreOperators = scoreOperatorRegistry.all(),
            allConditions = conditionRegistry.metadataList(),
            allConditionTrees = conditionTreeConfigService.loadAllMeta(),
            allTransforms = assembler.allTransforms().toList(),
            mapper = lin.ui.service.createTreeConfigMapper()
        )

        val uiState = RuleInteractionState(
            selectedGuardPayload = initialConfig?.guardCondition,
            currentScoreEffect = initialConfig?.scoreEffect
        )

        // ==========================================
        // 2. 初始化 Panel 布局组件 (外部引入)
        // ==========================================
        val guardPanel = GuardPanel(dataContext.allConditions, dataContext.allConditionTrees, dynamicFieldForm)
        val scorePanel =
            ScorePanel(dataContext.dataSources, dataContext.allTransforms, dataContext.scoreOperators, dynamicFieldForm)

        // ==========================================
        // 3. 事件与联动监听配置
        // ==========================================

        // 3.1 左侧 Guard 联动监听
        guardPanel.condCombo.selectionModel.selectedItemProperty().addListener { _, _, newCond ->
            guardPanel.condArgsContainer.children.clear()
            uiState.guardArgsMap.clear()
            if (newCond != null) {
                val specs = newCond.fields.map { it.fieldSpec }
                val form = dynamicFieldForm.build(
                    specs,
                    { propertyName -> uiState.guardArgsMap[propertyName] }) { prop, value ->
                    uiState.guardArgsMap[prop] = value
                }
                guardPanel.condArgsContainer.children.add(form)
            }
        }

        guardPanel.configBtn.setOnAction {
            val currentOrthogonal = uiState.selectedGuardPayload as? ConditionPayload.PipelineRef
            val dialog = OrthogonalConditionDialog(currentOrthogonal)
            val res = dialog.showAndWait()
            if (res.isPresent) {
                uiState.selectedGuardPayload = res.get()
                guardPanel.summaryLabel.text = "已配置: ${res.get().sourceId} -> ${res.get().operatorId}"
                guardPanel.summaryLabel.style = "-fx-text-fill: #333;"
            }
        }

        // 3.2 右侧 Score 联动监听
        scorePanel.sourceDsCombo.selectionModel.selectedItemProperty().addListener { _, _, newDs ->
            scorePanel.scorePipelineEditor.onDataSourceChanged(newDs?.outputType)
            scorePanel.sourceOpCombo.items.clear()
            scorePanel.sourceOpArgsContainer.children.clear()
            uiState.scoreArgsMap.clear()
        }

        scorePanel.sourceOpCombo.selectionModel.selectedItemProperty().addListener { _, _, newOp ->
            scorePanel.sourceOpArgsContainer.children.clear()
            uiState.scoreArgsMap.clear()
            if (newOp != null) {
                val specs = newOp.paramSpecs
                val form = dynamicFieldForm.build(
                    specs,
                    { propertyName -> uiState.scoreArgsMap[propertyName] }) { prop, value ->
                    uiState.scoreArgsMap[prop] = value
                }
                scorePanel.sourceOpArgsContainer.children.add(form)
            }
        }

        // ==========================================
        // 4. 详情区域构建与还原逻辑
        // ==========================================
        fun rebuildGuardDetail(newType: String?) {
            guardPanel.showType(newType ?: "无 (Always True)")
            uiState.guardArgsMap.clear()
            when (newType) {
                "普通条件" -> {
                    val currentPayload = uiState.selectedGuardPayload
                    if (currentPayload is ConditionPayload.ConditionRef && dataContext.allConditions.any { it.conditionId == currentPayload.conditionId }) {
                        val condMeta = dataContext.allConditions.first { it.conditionId == currentPayload.conditionId }
                        guardPanel.condCombo.selectionModel.select(condMeta)
                        uiState.guardArgsMap.putAll(currentPayload.args)
                        guardPanel.condArgsContainer.children.clear()
                        val specs = condMeta.fields.map { it.fieldSpec }
                        val form = dynamicFieldForm.build(
                            specs,
                            { propertyName -> uiState.guardArgsMap[propertyName] }) { prop, value ->
                            uiState.guardArgsMap[prop] = value
                        }
                        guardPanel.condArgsContainer.children.add(form)
                    }
                }

                "条件树" -> {
                    val currentPayload = uiState.selectedGuardPayload
                    if (currentPayload is ConditionPayload.ConditionRef && dataContext.allConditionTrees.any { it.first == currentPayload.conditionId }) {
                        val treeMeta = dataContext.allConditionTrees.first { it.first == currentPayload.conditionId }
                        guardPanel.treeCombo.selectionModel.select(treeMeta)
                    }
                }

                "正交条件" -> {
                    val currentOrthogonal = uiState.selectedGuardPayload as? ConditionPayload.PipelineRef
                    if (currentOrthogonal != null) {
                        guardPanel.summaryLabel.text =
                            "已配置: ${currentOrthogonal.sourceId} -> ${currentOrthogonal.operatorId}"
                        guardPanel.summaryLabel.style = "-fx-text-fill: #333;"
                    }
                }
            }
        }

        guardPanel.typeCombo.selectionModel.selectedItemProperty().addListener { _, _, newType ->
            rebuildGuardDetail(newType)
        }

        fun rebuildScoreDetail(newType: String?) {
            scorePanel.showType(newType ?: "固定分")
            uiState.scoreArgsMap.clear()
            when (newType) {
                "固定分" -> {
                    val existing = uiState.currentScoreEffect
                    if (existing is ScoreEffect.ConstantScore) {
                        scorePanel.constantValField.text = existing.value.toString()
                        scorePanel.constantMissField.text = existing.missValue.toString()
                    }
                }

                "数据源评分" -> {
                    val existing = uiState.currentScoreEffect
                    if (existing is ScoreEffect.SourceScore) {
                        val ds = dataContext.dataSources.firstOrNull { it.id == existing.sourceId }
                        if (ds != null) {
                            scorePanel.sourceDsCombo.selectionModel.select(ds)
                            scorePanel.scorePipelineEditor.loadTransforms(existing.transforms)

                            val op = dataContext.scoreOperators.firstOrNull { it.id == existing.operatorId }
                            if (op != null) {
                                scorePanel.sourceOpCombo.selectionModel.select(op)
                                uiState.scoreArgsMap.putAll(existing.operatorArgs)
                                scorePanel.sourceOpArgsContainer.children.clear()
                                val specs = op.paramSpecs
                                val form = dynamicFieldForm.build(
                                    specs,
                                    { propertyName -> uiState.scoreArgsMap[propertyName] }) { prop, value ->
                                    uiState.scoreArgsMap[prop] = value
                                }
                                scorePanel.sourceOpArgsContainer.children.add(form)
                            }
                        }
                        scorePanel.sourceMissField.text = existing.missValue.toString()
                    }
                }
            }
        }

        scorePanel.typeCombo.selectionModel.selectedItemProperty().addListener { _, _, newType ->
            rebuildScoreDetail(newType)
        }

        // ==========================================
        // 5. 配置加载逻辑
        // ==========================================
        fun loadConfig(ref: OrthogonalRuleLeafConfig) {
            uiState.selectedGuardPayload = ref.guardCondition
            uiState.currentScoreEffect = ref.scoreEffect

            val guardType = uiState.selectedGuardPayload?.let { payload ->
                when (payload) {
                    is ConditionPayload.PipelineRef -> "正交条件"
                    is ConditionPayload.ConditionRef -> {
                        if (dataContext.allConditionTrees.any { it.first == payload.conditionId }) "条件树" else "普通条件"
                    }
                }
            } ?: "无 (Always True)"
            guardPanel.typeCombo.selectionModel.select(guardType)
            rebuildGuardDetail(guardType)

            val scoreType = uiState.currentScoreEffect?.let { effect ->
                when (effect) {
                    is ScoreEffect.ConstantScore -> "固定分"
                    is ScoreEffect.SourceScore -> "数据源评分"
                }
            } ?: "固定分"
            scorePanel.typeCombo.selectionModel.select(scoreType)
            rebuildScoreDetail(scoreType)
        }

        initialConfig?.let { loadConfig(it) } ?: run {
            rebuildGuardDetail("无 (Always True)")
            rebuildScoreDetail("固定分")
        }

        // ==========================================
        // 6. 配置确定与导出 (buildCurrentConfig)
        // ==========================================
        fun buildCurrentConfig(): OrthogonalRuleLeafConfig? {
            val finalGuardPayload = when (guardPanel.typeCombo.value) {
                "普通条件" -> {
                    val selectedCond = guardPanel.condCombo.value
                    if (selectedCond != null) {
                        ConditionPayload.ConditionRef(
                            conditionId = selectedCond.conditionId,
                            refId = "${selectedCond.conditionId}_${UUID.randomUUID().toString().substring(0, 4)}",
                            args = HashMap(uiState.guardArgsMap)
                        )
                    } else null
                }

                "条件树" -> {
                    val selectedTree = guardPanel.treeCombo.value
                    if (selectedTree != null) {
                        ConditionPayload.ConditionRef(
                            conditionId = selectedTree.first,
                            refId = selectedTree.first
                        )
                    } else null
                }

                "正交条件" -> uiState.selectedGuardPayload
                else -> null
            }

            val finalScoreEffect = when (scorePanel.typeCombo.value) {
                "固定分" -> {
                    ScoreEffect.ConstantScore(
                        value = scorePanel.constantValField.text.toDoubleOrNull() ?: 0.0,
                        missValue = scorePanel.constantMissField.text.toDoubleOrNull() ?: 0.0
                    )
                }

                "数据源评分" -> {
                    val ds = scorePanel.sourceDsCombo.value
                    val op = scorePanel.sourceOpCombo.value
                    if (ds != null && op != null) {
                        ScoreEffect.SourceScore(
                            sourceId = ds.id,
                            transforms = scorePanel.scorePipelineEditor.getTransformCalls(),
                            operatorId = op.id,
                            operatorArgs = HashMap(uiState.scoreArgsMap),
                            missValue = scorePanel.sourceMissField.text.toDoubleOrNull() ?: 0.0
                        )
                    } else ScoreEffect.ConstantScore(0.0)
                }

                else -> ScoreEffect.ConstantScore(0.0)
            }

            return OrthogonalRuleLeafConfig(
                nodeId = initialConfig?.nodeId ?: "rule_${System.currentTimeMillis()}",
                sourceId = "orthogonal_rule",
                scoreEffect = finalScoreEffect,
                guardCondition = finalGuardPayload
            )
        }

        // ==========================================
        // 7. 整体布局管理与模板管理 UI
        // ==========================================
        val mainHBox = HBox(15.0).apply {
            children.addAll(guardPanel.root, scorePanel.root)
            HBox.setHgrow(guardPanel.root, Priority.ALWAYS)
            HBox.setHgrow(scorePanel.root, Priority.ALWAYS)
            prefWidth = 800.0
            prefHeight = 450.0
        }

        val templateCombo = ComboBox<lin.orthogonal_template.db.OrthogonalTemplateEntity>().apply {
            maxWidth = Double.MAX_VALUE
            promptText = "应用现有规则模板..."
        }

        fun reloadTemplates() {
            val templates = templateRepo.findAllByType("RULE")
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
                    val ref = dataContext.mapper.readValue(
                        template.contentJson,
                        EvaluatorLeafConfig::class.java
                    ) as? OrthogonalRuleLeafConfig
                    if (ref != null) {
                        loadConfig(ref)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    Alert(Alert.AlertType.ERROR, "加载规则模板失败: ${e.message}").showAndWait()
                }
            }
        }

        val saveTemplateBtn = Button("保存为模板...").apply {
            setOnAction {
                val currentConfig = buildCurrentConfig()
                if (currentConfig == null) {
                    Alert(Alert.AlertType.WARNING, "当前配置不完整，无法保存模板！").showAndWait()
                    return@setOnAction
                }

                val nameDialog = Dialog<Pair<String, String>>().apply {
                    title = "保存为正交规则模板"
                    headerText = "请输入模板的名称和描述"
                    val dialogPane = this.dialogPane
                    dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)

                    val nameInput = TextField().apply { promptText = "模板名称 (例如: 固定10分规则加手牌守卫)" }
                    val descInput = TextField().apply { promptText = "描述信息" }
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

                    val entity = lin.orthogonal_template.db.OrthogonalTemplateEntity(
                        id = "",
                        name = name,
                        description = desc.takeIf { it.isNotBlank() },
                        type = "RULE",
                        contentJson = dataContext.mapper.writeValueAsString(currentConfig)
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

        val templateHBox = HBox(8.0).apply {
            padding = Insets(10.0, 10.0, 0.0, 10.0)
            alignment = Pos.CENTER_LEFT
            children.addAll(Label("规则模板:"), templateCombo, saveTemplateBtn)
            HBox.setHgrow(templateCombo, Priority.ALWAYS)
        }

        val rootVBox = VBox(10.0).apply {
            children.addAll(templateHBox, mainHBox)
            VBox.setVgrow(mainHBox, Priority.ALWAYS)
        }
        dialogPane.content = rootVBox

        // setResult 转换
        setResultConverter { buttonType ->
            if (buttonType == ButtonType.OK) {
                buildCurrentConfig()
            } else {
                null
            }
        }
    }
}
