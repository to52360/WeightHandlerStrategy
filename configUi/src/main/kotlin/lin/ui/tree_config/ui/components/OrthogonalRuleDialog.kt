package lin.ui.tree_config.ui.components

import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.*
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import lin.db.OrthogonalTemplateEntity
import lin.db.OrthogonalTemplateRepository
import lin.db.TemplateGroupEntity
import lin.db.TemplateGroupRepository
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
import lin.ui.components.TemplateNameDialog
import lin.ui.condition_tree.ui.components.OrthogonalConditionDialog
import lin.ui.tree_config.ui.DynamicFieldForm
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.*

class OrthogonalRuleDialog(
    private val initialConfig: OrthogonalRuleLeafConfig? = null
) : Dialog<OrthogonalRuleLeafConfig>(), KoinComponent {

    private val conditionRegistry: ConditionRegistry by inject()
    private val scoreOperatorRegistry: ScoreOperatorRegistry by inject()
    private val conditionTreeConfigService: lin.ui.condition_tree.db.ConditionTreeConfigService by inject()

    private val dynamicFieldForm by lazy { DynamicFieldForm() }

    private val templateRepo: OrthogonalTemplateRepository by inject()
    private val templateGroupRepo: TemplateGroupRepository by inject()

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
            scorePanel.sourceOpCombo.items.clear()
            scorePanel.sourceOpArgsContainer.children.clear()
            uiState.scoreArgsMap.clear()
            scorePanel.scorePipelineEditor.onDataSourceChanged(newDs?.outputType)
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

        // 仅处理 SourceScore — 正交规则不包含 ConstantScore
        fun loadScoreDetail(existing: ScoreEffect?) {
            uiState.scoreArgsMap.clear()
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
                scorePanel.crossCardCheckBox.isSelected = existing.crossCard
            }
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

            loadScoreDetail(uiState.currentScoreEffect)
        }

        initialConfig?.let { loadConfig(it) } ?: run {
            rebuildGuardDetail("无 (Always True)")
            loadScoreDetail(null)
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

            val finalScoreEffect = run {
                val ds = scorePanel.sourceDsCombo.value
                val op = scorePanel.sourceOpCombo.value
                if (ds != null && op != null) {
                    ScoreEffect.SourceScore(
                        sourceId = ds.id,
                        transforms = scorePanel.scorePipelineEditor.getTransformCalls(),
                        operatorId = op.id,
                        operatorArgs = HashMap(uiState.scoreArgsMap),
                        crossCard = scorePanel.crossCardCheckBox.isSelected,
                        missValue = scorePanel.sourceMissField.text.toDoubleOrNull() ?: 0.0
                    )
                } else {
                    // 未完整配置时使用空 SourceScore，OK 按钮验证会拦截
                    ScoreEffect.SourceScore("", emptyList(), "", emptyMap(), false, 0.0)
                }
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

        val templateCombo = ComboBox<OrthogonalTemplateEntity>().apply {
            maxWidth = Double.MAX_VALUE
            promptText = "应用现有规则模板..."
        }

        fun reloadTemplates() {
            val selectedGroupId = groupFilterCombo.value?.id
            val allTemplates = templateRepo.findAllByType("RULE")
            templateCombo.items.setAll(
                if (selectedGroupId.isNullOrBlank()) allTemplates
                else allTemplates.filter { it.groupId == selectedGroupId }
            )
        }
        reloadTemplates()

        groupFilterCombo.selectionModel.selectedItemProperty().addListener { _, _, _ -> reloadTemplates() }

        TemplateNameDialog.applyTemplateEntityCellFactory(templateCombo)

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

                // T-015: 模板只存结构不含参数 (D-007)
                val strippedConfig = currentConfig.copy(
                    nodeId = "",
                    args = emptyMap(),
                    guardCondition = currentConfig.guardCondition?.let { stripConditionArgs(it) },
                    scoreEffect = stripScoreArgs(currentConfig.scoreEffect)
                )

                val nameDialog = TemplateNameDialog(
                    title = "保存为正交规则模板",
                    headerText = "请输入模板的名称、描述和分组",
                    namePromptText = "模板名称 (例如: 固定10分规则加手牌守卫)",
                    descPromptText = "描述信息"
                )

                val res = nameDialog.showAndWait()
                if (res.isPresent) {
                    val (name, desc, groupId) = res.get()
                    if (name.isBlank()) {
                        Alert(Alert.AlertType.WARNING, "模板名称不能为空！").showAndWait()
                        return@setOnAction
                    }

                    val entity = OrthogonalTemplateEntity(
                        id = "",
                        name = name,
                        description = desc.takeIf { it.isNotBlank() },
                        groupId = groupId,
                        type = "RULE",
                        contentJson = dataContext.mapper.writeValueAsString(strippedConfig)
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
            children.addAll(
                Label("分组:"), groupFilterCombo,
                Label("模板:"), templateCombo, saveTemplateBtn
            )
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

// T-015: 模板参数剥离 (D-007) — 只保留结构引用，去除所有运行时参数值

private fun stripConditionArgs(payload: ConditionPayload): ConditionPayload = when (payload) {
    is ConditionPayload.PipelineRef -> payload.copy(
        operatorArgs = emptyMap(),
        transforms = payload.transforms.map { it.copy(args = emptyMap()) },
        refId = ""
    )

    is ConditionPayload.ConditionRef -> payload.copy(args = emptyMap())
}

private fun stripScoreArgs(scoreEffect: ScoreEffect): ScoreEffect = when (scoreEffect) {
    is ScoreEffect.SourceScore -> scoreEffect.copy(
        operatorArgs = emptyMap(),
        transforms = scoreEffect.transforms.map { it.copy(args = emptyMap()) }
    )

    else -> scoreEffect
}
