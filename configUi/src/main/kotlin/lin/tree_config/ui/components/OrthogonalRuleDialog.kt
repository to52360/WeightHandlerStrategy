package lin.tree_config.ui.components

import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.*
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import lin.condition_tree.db.ConditionTreeConfigService
import lin.condition_tree.ui.components.OrthogonalConditionDialog
import lin.rule.condition.ConditionMeta
import lin.rule.condition.ConditionPayload
import lin.rule.condition.ConditionRegistry
import lin.rule.orthogonal.DataSource
import lin.rule.score.ScoreEffect
import lin.rule.score.ScoreOperator
import lin.rule.score.ScoreOperatorRegistry
import lin.rule.tree.EvaluatorLeafConfig
import lin.rule.tree.OrthogonalRuleLeafConfig
import lin.tree_config.ui.DynamicFieldForm
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.*
import kotlin.reflect.KType
import kotlin.reflect.full.isSubtypeOf

class OrthogonalRuleDialog(
    private val initialConfig: OrthogonalRuleLeafConfig? = null
) : Dialog<OrthogonalRuleLeafConfig>(), KoinComponent {

    private val conditionRegistry: ConditionRegistry by inject()
    private val scoreOperatorRegistry: ScoreOperatorRegistry by inject()
    private val conditionTreeConfigService: ConditionTreeConfigService by inject()

    private val dynamicFieldForm by lazy { DynamicFieldForm() }

    private val templateRepo: lin.orthogonal_template.db.OrthogonalTemplateRepository by inject()

    init {
        title = "配置正交规则"
        headerText = "正交规则由 守卫条件 (Guard) + 评分效应 (ScoreEffect) 两个维度组合，左侧配置守卫，右侧配置算分"

        val dialogPane = this.dialogPane
        dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)

        // ==========================================
        // 1. 数据准备
        // ==========================================
        val assembler = conditionRegistry.pipelineAssembler
            ?: error("PipelineAssembler is not configured in this context")
        val dataSources = assembler.allDataSources().toList()
        val scoreOperators = scoreOperatorRegistry.all()
        val allConditions = conditionRegistry.metadataList()
        val allConditionTrees = conditionTreeConfigService.loadAllMeta()
        val mapper = lin.ui.service.createTreeConfigMapper()

        // 状态保存变量
        var selectedGuardPayload: ConditionPayload? = initialConfig?.guardCondition
        var currentScoreEffect: ScoreEffect? = initialConfig?.scoreEffect
        val guardArgsMap = mutableMapOf<String, Any>()
        val scoreArgsMap = mutableMapOf<String, Any>()

        // ==========================================
        // 2. 左侧：守卫条件配置 (Guard)
        // ==========================================
        val guardPane = VBox(10.0).apply {
            padding = Insets(10.0)
            style = "-fx-border-color: #ddd; -fx-border-radius: 4px; -fx-background-color: #fafafa;"
        }
        guardPane.children.add(Label("守卫条件 (Guard)").apply {
            style = "-fx-font-weight: bold; -fx-font-size: 14px;"
        })

        val guardTypeCombo = ComboBox<String>().apply {
            items.addAll("无 (Always True)", "普通条件", "条件树", "正交条件")
            selectionModel.selectFirst()
            maxWidth = Double.MAX_VALUE
        }
        guardPane.children.add(HBox(8.0, Label("条件类型:"), guardTypeCombo).apply { alignment = Pos.CENTER_LEFT })

        val guardDetailArea = VBox(8.0)
        guardPane.children.add(guardDetailArea)

        fun rebuildGuardDetail(newType: String?) {
            guardDetailArea.children.clear()
            guardArgsMap.clear()
            when (newType) {
                "普通条件" -> {
                    val condCombo = ComboBox<ConditionMeta>().apply {
                        items.addAll(allConditions)
                        maxWidth = Double.MAX_VALUE
                        setCellFactory { createConditionMetaCell() }
                        buttonCell = createConditionMetaCell()
                    }
                    val condArgsContainer = VBox(5.0)
                    guardDetailArea.children.addAll(condCombo, condArgsContainer)

                    condCombo.selectionModel.selectedItemProperty().addListener { _, _, newCond ->
                        condArgsContainer.children.clear()
                        guardArgsMap.clear()
                        if (newCond != null) {
                            val specs = newCond.fields.map { it.fieldSpec }
                            val form = dynamicFieldForm.build(
                                specs,
                                { propertyName -> guardArgsMap[propertyName] }) { prop, value ->
                                guardArgsMap[prop] = value
                            }
                            condArgsContainer.children.add(form)
                        }
                    }

                    // 还原普通条件初始状态
                    val currentPayload = selectedGuardPayload
                    if (currentPayload is ConditionPayload.ConditionRef && allConditions.any { it.conditionId == currentPayload.conditionId }) {
                        val condMeta = allConditions.first { it.conditionId == currentPayload.conditionId }
                        condCombo.selectionModel.select(condMeta)
                        guardArgsMap.putAll(currentPayload.args)
                        condArgsContainer.children.clear()
                        val specs = condMeta.fields.map { it.fieldSpec }
                        val form = dynamicFieldForm.build(
                            specs,
                            { propertyName -> guardArgsMap[propertyName] }) { prop, value ->
                            guardArgsMap[prop] = value
                        }
                        condArgsContainer.children.add(form)
                    }
                }

                "条件树" -> {
                    val treeCombo = ComboBox<Pair<String, String>>().apply {
                        items.addAll(allConditionTrees)
                        maxWidth = Double.MAX_VALUE
                        setCellFactory { createConditionTreeCell() }
                        buttonCell = createConditionTreeCell()
                    }
                    guardDetailArea.children.add(treeCombo)

                    // 还原条件树初始状态
                    val currentPayload = selectedGuardPayload
                    if (currentPayload is ConditionPayload.ConditionRef && allConditionTrees.any { it.first == currentPayload.conditionId }) {
                        val treeMeta = allConditionTrees.first { it.first == currentPayload.conditionId }
                        treeCombo.selectionModel.select(treeMeta)
                    }
                }

                "正交条件" -> {
                    val configBtn = Button("编辑正交条件...")
                    val summaryLabel =
                        Label("未配置正交条件").apply { style = "-fx-text-fill: #888; -fx-font-style: italic;" }
                    guardDetailArea.children.addAll(configBtn, summaryLabel)

                    // 还原正交条件初始状态
                    var currentOrthogonal = selectedGuardPayload as? ConditionPayload.PipelineRef
                    if (currentOrthogonal != null) {
                        summaryLabel.text = "已配置: ${currentOrthogonal.sourceId} -> ${currentOrthogonal.operatorId}"
                        summaryLabel.style = "-fx-text-fill: #333;"
                    }

                    configBtn.setOnAction {
                        val dialog = OrthogonalConditionDialog(currentOrthogonal)
                        val res = dialog.showAndWait()
                        if (res.isPresent) {
                            currentOrthogonal = res.get()
                            selectedGuardPayload = res.get()
                            summaryLabel.text = "已配置: ${res.get().sourceId} -> ${res.get().operatorId}"
                            summaryLabel.style = "-fx-text-fill: #333;"
                        }
                    }
                }
            }
        }

        // 守卫类型切换监听
        guardTypeCombo.selectionModel.selectedItemProperty().addListener { _, _, newType ->
            rebuildGuardDetail(newType)
        }

        // ==========================================
        // 3. 右侧：评分效应配置 (ScoreEffect)
        // ==========================================
        val scorePane = VBox(10.0).apply {
            padding = Insets(10.0)
            style = "-fx-border-color: #ddd; -fx-border-radius: 4px; -fx-background-color: #fafafa;"
        }
        scorePane.children.add(Label("评分效应 (ScoreEffect)").apply {
            style = "-fx-font-weight: bold; -fx-font-size: 14px;"
        })

        val scoreTypeCombo = ComboBox<String>().apply {
            items.addAll("固定分", "数据源评分")
            selectionModel.selectFirst()
            maxWidth = Double.MAX_VALUE
        }
        scorePane.children.add(HBox(8.0, Label("评分类型:"), scoreTypeCombo).apply { alignment = Pos.CENTER_LEFT })

        val scoreDetailArea = VBox(8.0)
        scorePane.children.add(scoreDetailArea)

        fun rebuildScoreDetail(newType: String?) {
            scoreDetailArea.children.clear()
            scoreArgsMap.clear()
            when (newType) {
                "固定分" -> {
                    val valField = TextField("0.0")
                    val missField = TextField("0.0")
                    val grid = GridPane().apply { hgap = 8.0; vgap = 8.0 }
                    grid.add(Label("命中分数:"), 0, 0)
                    grid.add(valField, 1, 0)
                    grid.add(Label("未命中分数:"), 0, 1)
                    grid.add(missField, 1, 1)
                    scoreDetailArea.children.add(grid)

                    // 还原固定评分初始状态
                    val existing = currentScoreEffect
                    if (existing is ScoreEffect.ConstantScore) {
                        valField.text = existing.value.toString()
                        missField.text = existing.missValue.toString()
                    }
                }

                "数据源评分" -> {
                    val dsCombo = ComboBox<DataSource<*>>().apply {
                        items.addAll(dataSources)
                        maxWidth = Double.MAX_VALUE
                        setCellFactory { createDataSourceCell() }
                        buttonCell = createDataSourceCell()
                    }
                    val opCombo = ComboBox<ScoreOperator<*, *>>().apply {
                        maxWidth = Double.MAX_VALUE
                        setCellFactory { createScoreOperatorCell() }
                        buttonCell = createScoreOperatorCell()
                        isDisable = true
                    }
                    val opArgsContainer = VBox(5.0)
                    val missField = TextField("0.0")
                    val grid = GridPane().apply { hgap = 8.0; vgap = 8.0 }
                    grid.add(Label("评分数据源:"), 0, 0)
                    grid.add(dsCombo, 1, 0)
                    grid.add(Label("评分算子:"), 0, 1)
                    grid.add(opCombo, 1, 1)
                    grid.add(Label("算子参数:"), 0, 2)
                    grid.add(opArgsContainer, 1, 2)
                    grid.add(Label("未命中分数:"), 0, 3)
                    grid.add(missField, 1, 3)

                    scoreDetailArea.children.add(grid)

                    // 数据源联动
                    dsCombo.selectionModel.selectedItemProperty().addListener { _, _, newDs ->
                        opCombo.items.clear()
                        opArgsContainer.children.clear()
                        scoreArgsMap.clear()
                        if (newDs != null) {
                            val compatibleOps = scoreOperators.filter { isCompatible(it.inputType, newDs.outputType) }
                            opCombo.items.setAll(compatibleOps)
                            opCombo.isDisable = false
                        } else {
                            opCombo.isDisable = true
                        }
                    }

                    // 算子联动
                    opCombo.selectionModel.selectedItemProperty().addListener { _, _, newOp ->
                        opArgsContainer.children.clear()
                        scoreArgsMap.clear()
                        if (newOp != null) {
                            val specs = newOp.paramSpecs
                            val form = dynamicFieldForm.build(
                                specs,
                                { propertyName -> scoreArgsMap[propertyName] }) { prop, value ->
                                scoreArgsMap[prop] = value
                            }
                            opArgsContainer.children.add(form)
                        }
                    }

                    // 还原数据源评分初始状态
                    val existing = currentScoreEffect
                    if (existing is ScoreEffect.SourceScore) {
                        val ds = dataSources.firstOrNull { it.id == existing.sourceId }
                        if (ds != null) {
                            dsCombo.selectionModel.select(ds)
                            val compatibleOps = scoreOperators.filter { isCompatible(it.inputType, ds.outputType) }
                            opCombo.items.setAll(compatibleOps)
                            opCombo.isDisable = false

                            val op = compatibleOps.firstOrNull { it.id == existing.operatorId }
                            if (op != null) {
                                opCombo.selectionModel.select(op)
                                scoreArgsMap.putAll(existing.operatorArgs)
                                opArgsContainer.children.clear()
                                val specs = op.paramSpecs
                                val form = dynamicFieldForm.build(
                                    specs,
                                    { propertyName -> scoreArgsMap[propertyName] }) { prop, value ->
                                    scoreArgsMap[prop] = value
                                }
                                opArgsContainer.children.add(form)
                            }
                        }
                        missField.text = existing.missValue.toString()
                    }
                }
            }
        }

        // 评分类型切换监听
        scoreTypeCombo.selectionModel.selectedItemProperty().addListener { _, _, newType ->
            rebuildScoreDetail(newType)
        }

        fun loadConfig(ref: OrthogonalRuleLeafConfig) {
            selectedGuardPayload = ref.guardCondition
            currentScoreEffect = ref.scoreEffect

            // 恢复 Guard
            val guardType = selectedGuardPayload?.let { payload ->
                when (payload) {
                    is ConditionPayload.PipelineRef -> "正交条件"
                    is ConditionPayload.ConditionRef -> {
                        if (allConditionTrees.any { it.first == payload.conditionId }) "条件树" else "普通条件"
                    }
                }
            } ?: "无 (Always True)"
            guardTypeCombo.selectionModel.select(guardType)
            rebuildGuardDetail(guardType)

            // 恢复 Score
            val scoreType = currentScoreEffect?.let { effect ->
                when (effect) {
                    is ScoreEffect.ConstantScore -> "固定分"
                    is ScoreEffect.SourceScore -> "数据源评分"
                }
            } ?: "固定分"
            scoreTypeCombo.selectionModel.select(scoreType)
            rebuildScoreDetail(scoreType)
        }

        // ==========================================
        // 4. 还原初始化状态
        // ==========================================
        initialConfig?.let { loadConfig(it) } ?: run {
            rebuildGuardDetail("无 (Always True)")
            rebuildScoreDetail("固定分")
        }

        // ==========================================
        // 5. 整体组装与模板管理
        // ==========================================
        val mainHBox = HBox(15.0).apply {
            children.addAll(guardPane, scorePane)
            HBox.setHgrow(guardPane, Priority.ALWAYS)
            HBox.setHgrow(scorePane, Priority.ALWAYS)
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
                    val ref = mapper.readValue(
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

        fun buildCurrentConfig(): OrthogonalRuleLeafConfig? {
            // 1. 获取 Guard Payload
            val finalGuardPayload = when (guardTypeCombo.value) {
                "普通条件" -> {
                    val condCombo = guardDetailArea.children.firstOrNull() as? ComboBox<ConditionMeta>
                    val selectedCond = condCombo?.value
                    if (selectedCond != null) {
                        ConditionPayload.ConditionRef(
                            conditionId = selectedCond.conditionId,
                            refId = "${selectedCond.conditionId}_${UUID.randomUUID().toString().substring(0, 4)}",
                            args = HashMap(guardArgsMap)
                        )
                    } else null
                }

                "条件树" -> {
                    val treeCombo = guardDetailArea.children.firstOrNull() as? ComboBox<Pair<String, String>>
                    val selectedTree = treeCombo?.value
                    if (selectedTree != null) {
                        ConditionPayload.ConditionRef(
                            conditionId = selectedTree.first,
                            refId = selectedTree.first
                        )
                    } else null
                }

                "正交条件" -> selectedGuardPayload
                else -> null
            }

            // 2. 获取 ScoreEffect
            val finalScoreEffect = when (scoreTypeCombo.value) {
                "固定分" -> {
                    val grid = scoreDetailArea.children.firstOrNull() as? GridPane
                    val valField = grid?.children?.filterIsInstance<TextField>()?.firstOrNull()
                    val missField = grid?.children?.filterIsInstance<TextField>()?.getOrNull(1)
                    ScoreEffect.ConstantScore(
                        value = valField?.text?.toDoubleOrNull() ?: 0.0,
                        missValue = missField?.text?.toDoubleOrNull() ?: 0.0
                    )
                }

                "数据源评分" -> {
                    val grid = scoreDetailArea.children.firstOrNull() as? GridPane
                    val dsCombo = grid?.children?.filterIsInstance<ComboBox<DataSource<*>>>()?.firstOrNull()
                    val opCombo = grid?.children?.filterIsInstance<ComboBox<ScoreOperator<*, *>>>()?.firstOrNull()
                    val missField = grid?.children?.filterIsInstance<TextField>()?.firstOrNull()
                    val ds = dsCombo?.value
                    val op = opCombo?.value
                    if (ds != null && op != null) {
                        ScoreEffect.SourceScore(
                            sourceId = ds.id,
                            transforms = emptyList(),
                            operatorId = op.id,
                            operatorArgs = HashMap(scoreArgsMap),
                            missValue = missField?.text?.toDoubleOrNull() ?: 0.0
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
                        contentJson = mapper.writeValueAsString(currentConfig)
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
