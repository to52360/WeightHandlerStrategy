package lin.tree_config.ui.strategy

import javafx.geometry.Pos
import javafx.scene.control.*
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import lin.condition_tree.ui.components.OrthogonalConditionDialog
import lin.rule.condition.ConditionPayload
import lin.rule.parse.FieldSpec
import lin.rule.score.ScoreEffect
import lin.rule.score.ScoreOperatorRegistry
import lin.rule.tree.*
import lin.tree_config.db.EvaluatorLeafSourceCatalog
import lin.tree_config.ui.DynamicFieldForm
import lin.tree_config.ui.LogicNodeType
import lin.tree_config.ui.LogicNodeWrapper
import lin.tree_config.ui.components.OrthogonalRuleDialog
import lin.ui.components.PropertyEditorStrategy

class EvaluatorPropertyEditorStrategy(
    private val leafSourceCatalog: EvaluatorLeafSourceCatalog,
    private val scoreOperatorRegistry: ScoreOperatorRegistry,
    private val leafConfigs: MutableMap<String, EvaluatorLeafConfig>
) : PropertyEditorStrategy<EvaluatorPayload> {

    private val dynamicFieldForm by lazy { DynamicFieldForm() }

    override fun canEdit(type: LogicNodeType): Boolean {
        return type == LogicNodeType.LEAF || type == LogicNodeType.BRANCH
    }

    override fun render(panel: VBox, wrapper: LogicNodeWrapper<EvaluatorPayload>, onChanged: () -> Unit) {
        panel.children.clear()

        val nodeId = when (val payload = wrapper.payload) {
            is EvaluatorPayload.Rule -> payload.nodeId
            is EvaluatorPayload.BranchCondition -> payload.nodeId
            else -> return
        }
        val existing = leafConfigs[nodeId]
        val isBranch = wrapper.type == LogicNodeType.BRANCH

        panel.children.addAll(buildHeader(nodeId, isBranch))
        panel.children.add(Separator())

        val allLeafItems = loadLeafUiItems()

        val categoryCombo = ComboBox<EvaluatorLeafSourceType>().apply {
            maxWidth = Double.MAX_VALUE
            val categories = if (isBranch) {
                listOf(EvaluatorLeafSourceType.CONDITION, EvaluatorLeafSourceType.CONDITION_TREE)
            } else {
                EvaluatorLeafSourceType.values().toList()
            }
            items.addAll(categories)
            setCellFactory { createCategoryCell() }
            buttonCell = createCategoryCell()
        }

        val leafSourceCombo = ComboBox<EvaluatorLeafMeta>().apply {
            maxWidth = Double.MAX_VALUE
            setCellFactory { createLeafItemCell() }
            buttonCell = createLeafItemCell()
        }

        // 联动逻辑：切换分类时，重新填充具体项下拉框，并恢复或默认选中
        categoryCombo.selectionModel.selectedItemProperty().addListener { _, _, newCategory ->
            leafSourceCombo.items.clear()
            if (newCategory != null) {
                val filtered = allLeafItems.filter { it.sourceType == newCategory }
                leafSourceCombo.items.addAll(filtered)
                val toSelect = if (existing != null && existing.sourceType == newCategory) {
                    filtered.firstOrNull { it.sourceId == existing.sourceId }
                } else {
                    null
                } ?: filtered.firstOrNull()

                if (toSelect != null) {
                    leafSourceCombo.selectionModel.select(toSelect)
                } else {
                    leafSourceCombo.selectionModel.clearSelection()
                }
            }
        }

        // 初始化选中分类（会触发联动填充并选中 leafSourceCombo）
        val initialCategory =
            existing?.sourceType ?: if (isBranch) EvaluatorLeafSourceType.CONDITION else EvaluatorLeafSourceType.RULE
        categoryCombo.selectionModel.select(initialCategory)

        val dynamicFormArea = VBox(8.0)

        // 联动渲染函数
        fun renderEditor(selectedLeaf: EvaluatorLeafMeta) {
            dynamicFormArea.children.clear()
            val currentConfig = leafConfigs[nodeId]
            val currentArgs = currentConfig?.args?.toMutableMap() ?: mutableMapOf()

            when {
                // 情景一：正交规则
                selectedLeaf.sourceType == EvaluatorLeafSourceType.ORTHOGONAL_RULE -> {
                    val btn = Button("编辑正交规则配置...").apply { maxWidth = Double.MAX_VALUE }
                    val summaryLabel = Label(
                        (currentConfig as? OrthogonalRuleLeafConfig)?.scoreEffect?.let { "已配置评分，守卫: ${if (currentConfig.guardCondition != null) "有" else "无"}" }
                            ?: "未配置正交规则"
                    ).apply {
                        style =
                            if ((currentConfig as? OrthogonalRuleLeafConfig)?.scoreEffect != null) "-fx-text-fill: #333;" else "-fx-text-fill: #888; -fx-font-style: italic;"
                    }

                    btn.setOnAction {
                        val dialog = OrthogonalRuleDialog(currentConfig as? OrthogonalRuleLeafConfig)
                        val res = dialog.showAndWait()
                        if (res.isPresent) {
                            val newConfig = res.get()
                            val savedConfig = newConfig.copy(nodeId = nodeId)
                            leafConfigs[nodeId] = savedConfig
                            summaryLabel.text =
                                "已配置评分，守卫: ${if (savedConfig.guardCondition != null) "有" else "无"}"
                            summaryLabel.style = "-fx-text-fill: #333;"
                            onChanged()
                        }
                    }
                    dynamicFormArea.children.addAll(btn, summaryLabel)
                }

                // 情景二：正交条件 (无论是 Branch 还是 Leaf)
                selectedLeaf.sourceType == EvaluatorLeafSourceType.CONDITION && selectedLeaf.sourceId == "orthogonal_condition" -> {
                    val btn = Button("配置正交条件详情...").apply { maxWidth = Double.MAX_VALUE }
                    val currentOrtho =
                        (currentConfig as? ConditionLeafConfig)?.guardCondition as? ConditionPayload.OrthogonalRef
                    val summaryLabel = Label(
                        currentOrtho?.let { "正交条件: ${it.sourceId} -> ${it.operatorId}" } ?: "未配置正交条件"
                    ).apply {
                        style =
                            if (currentOrtho != null) "-fx-text-fill: #333;" else "-fx-text-fill: #888; -fx-font-style: italic;"
                    }

                    btn.setOnAction {
                        val dialog = OrthogonalConditionDialog(currentOrtho)
                        val res = dialog.showAndWait()
                        if (res.isPresent) {
                            val orthoRef = res.get()
                            val scoreEffect =
                                (currentConfig as? ConditionLeafConfig)?.scoreEffect ?: ScoreEffect.ConstantScore(0.0)
                            val newConfig = ConditionLeafConfig(
                                nodeId = nodeId,
                                sourceId = "orthogonal_condition",
                                scoreEffect = scoreEffect,
                                guardCondition = orthoRef
                            )
                            leafConfigs[nodeId] = newConfig
                            summaryLabel.text = "正交条件: ${orthoRef.sourceId} -> ${orthoRef.operatorId}"
                            summaryLabel.style = "-fx-text-fill: #333;"
                            onChanged()
                        }
                    }
                    dynamicFormArea.children.addAll(btn, summaryLabel)

                    // 如果不是 Branch 节点，正交条件作为叶子时，支持输入分数 (ConstantScore)
                    if (!isBranch) {
                        dynamicFormArea.children.add(Separator())
                        val specs = CONDITION_BUILT_IN_FIELDS
                        val form = dynamicFieldForm.build(
                            specs,
                            { prop -> fieldValueOf(prop, leafConfigs[nodeId]) }) { prop, value ->
                            currentArgs[prop] = value
                            updateLeafConfig(nodeId, selectedLeaf, leafConfigs[nodeId], currentArgs)
                            onChanged()
                        }
                        dynamicFormArea.children.add(form)
                    }
                }

                // 情景三：常规 RULE/CONDITION/CONDITION_TREE
                else -> {
                    buildDynamicForm(
                        dynamicFormArea,
                        selectedLeaf,
                        nodeId,
                        leafSourceCombo,
                        leafConfigs[nodeId],
                        currentArgs,
                        isBranch
                    )

                    // 🌟 对常规非 Branch 节点，额外在最下方展示“通用外挂守卫配置”区
                    if (!isBranch) {
                        dynamicFormArea.children.add(Separator())
                        val guardBox = HBox(8.0).apply { alignment = Pos.CENTER_LEFT }
                        val currentGuard = when (val c = leafConfigs[nodeId]) {
                            is RuleLeafConfig -> c.guardCondition
                            is OrthogonalRuleLeafConfig -> c.guardCondition
                            is ConditionLeafConfig -> c.guardCondition
                            else -> null
                        } as? ConditionPayload.OrthogonalRef
                        val guardSummary = Label(
                            currentGuard?.let { "守卫: ${it.sourceId} -> ${it.operatorId}" } ?: "无通用守卫"
                        ).apply {
                            style =
                                if (currentGuard != null) "-fx-text-fill: #333;" else "-fx-text-fill: #888; -fx-font-style: italic;"
                        }

                        val configGuardBtn = Button("配置通用守卫...")
                        val clearGuardBtn = Button("清除").apply { isDisable = (currentGuard == null) }

                        configGuardBtn.setOnAction {
                            val dialog = OrthogonalConditionDialog(currentGuard)
                            val res = dialog.showAndWait()
                            if (res.isPresent) {
                                val guardRef = res.get()
                                val oldConfig = leafConfigs[nodeId] ?: buildEvaluatorLeafConfig(
                                    nodeId,
                                    selectedLeaf,
                                    null,
                                    currentArgs
                                )
                                val newConfig = when (oldConfig) {
                                    is RuleLeafConfig -> oldConfig.copy(guardCondition = guardRef)
                                    is OrthogonalRuleLeafConfig -> oldConfig.copy(guardCondition = guardRef)
                                    is ConditionLeafConfig -> oldConfig.copy(guardCondition = guardRef)
                                    is ConditionTreeLeafConfig -> oldConfig
                                }
                                leafConfigs[nodeId] = newConfig
                                guardSummary.text = "守卫: ${guardRef.sourceId} -> ${guardRef.operatorId}"
                                guardSummary.style = "-fx-text-fill: #333;"
                                clearGuardBtn.isDisable = false
                                onChanged()
                            }
                        }

                        clearGuardBtn.setOnAction {
                            val oldConfig =
                                leafConfigs[nodeId] ?: buildEvaluatorLeafConfig(nodeId, selectedLeaf, null, currentArgs)
                            val newConfig = when (oldConfig) {
                                is RuleLeafConfig -> oldConfig.copy(guardCondition = null)
                                is OrthogonalRuleLeafConfig -> oldConfig.copy(guardCondition = null)
                                is ConditionLeafConfig -> oldConfig.copy(guardCondition = null)
                                is ConditionTreeLeafConfig -> oldConfig
                            }
                            leafConfigs[nodeId] = newConfig
                            guardSummary.text = "无通用守卫"
                            guardSummary.style = "-fx-text-fill: #888; -fx-font-style: italic;"
                            clearGuardBtn.isDisable = true
                            onChanged()
                        }

                        guardBox.children.addAll(Label("外挂守卫:"), configGuardBtn, clearGuardBtn, guardSummary)
                        dynamicFormArea.children.add(guardBox)
                    }
                }
            }
        }

        // 添加 leafSourceCombo 监听器以处理后续手动修改
        leafSourceCombo.selectionModel.selectedItemProperty().addListener { _, _, selectedLeaf ->
            if (selectedLeaf != null) {
                val currentArgs = leafConfigs[nodeId]?.args?.toMutableMap() ?: mutableMapOf()
                updateLeafConfig(nodeId, selectedLeaf, leafConfigs[nodeId], currentArgs)
                renderEditor(selectedLeaf)
                onChanged()
            }
        }

        panel.children.addAll(
            HBox(8.0).apply {
                alignment = Pos.CENTER_LEFT
                children.addAll(
                    Label("分类:"), categoryCombo,
                    Label("策略:"), leafSourceCombo
                )
                HBox.setHgrow(categoryCombo, Priority.ALWAYS)
                HBox.setHgrow(leafSourceCombo, Priority.ALWAYS)
            },
            dynamicFormArea
        )

        // 恢复选中项渲染
        val selectedItem = leafSourceCombo.selectionModel.selectedItem
        if (selectedItem != null) {
            renderEditor(selectedItem)
        }
    }

    private fun createCategoryCell(): ListCell<EvaluatorLeafSourceType> {
        return object : ListCell<EvaluatorLeafSourceType>() {
            override fun updateItem(item: EvaluatorLeafSourceType?, empty: Boolean) {
                super.updateItem(item, empty)
                text = if (empty || item == null) null
                else when (item) {
                    EvaluatorLeafSourceType.CONDITION -> "普通条件"
                    EvaluatorLeafSourceType.CONDITION_TREE -> "条件树"
                    EvaluatorLeafSourceType.RULE -> "硬编码规则"
                    EvaluatorLeafSourceType.ORTHOGONAL_RULE -> "正交规则"
                }
            }
        }
    }

    private fun buildHeader(nodeId: String, isBranch: Boolean): List<javafx.scene.Node> {
        val typeLabel = if (isBranch) "Branch 节点" else "Rule 节点"
        val nodes = mutableListOf<javafx.scene.Node>(
            Label("$typeLabel (nodeId: $nodeId)").apply {
                style = "-fx-font-weight: bold;"
            }
        )
        if (isBranch) {
            nodes.add(
                Label("条件满足时设置分支，子节点[0]=onTrue, [1]=onFalse").apply {
                    style = "-fx-text-fill: #888; -fx-font-size: 11px;"
                }
            )
        }
        return nodes
    }

    private fun buildDynamicForm(
        container: VBox,
        uiItem: EvaluatorLeafMeta,
        nodeId: String,
        leafSourceCombo: ComboBox<EvaluatorLeafMeta>,
        existing: EvaluatorLeafConfig?,
        args: MutableMap<String, Any>,
        isBranch: Boolean = false
    ) {
        container.children.clear()

        val specs = computeFieldSpecs(uiItem, args, existing, isBranch)

        val form = dynamicFieldForm.build(
            specs = specs,
            existingValues = { propertyName -> fieldValueOf(propertyName, existing) },
        ) { propertyName, value ->
            args[propertyName] = value
            updateLeafConfig(nodeId, leafSourceCombo.value, existing, args)

            if (propertyName == EVALUATOR_LEAF_SCORE_OPERATOR_FIELD ||
                propertyName == EVALUATOR_LEAF_SCORE_EFFECT_TYPE_FIELD
            ) {
                val updatedExisting = leafConfigs[nodeId]
                buildDynamicForm(
                    container, uiItem, nodeId, leafSourceCombo,
                    updatedExisting, mutableMapOf(), isBranch
                )
            }
        }

        container.children.add(form)
    }

    private fun computeFieldSpecs(
        uiItem: EvaluatorLeafMeta,
        args: Map<String, Any>,
        existing: EvaluatorLeafConfig?,
        isBranch: Boolean
    ): List<FieldSpec> {
        if (isBranch) return uiItem.fields

        val builtIn = uiItem.builtInFields.toMutableList()

        val existingScoreEffect = when (existing) {
            is OrthogonalRuleLeafConfig -> existing.scoreEffect
            is ConditionLeafConfig -> existing.scoreEffect
            is ConditionTreeLeafConfig -> existing.scoreEffect
            else -> null
        }

        val effectType = args[EVALUATOR_LEAF_SCORE_EFFECT_TYPE_FIELD] as? String
            ?: when (existingScoreEffect) {
                is ScoreEffect.SourceScore -> SCORE_EFFECT_TYPE_SOURCE
                else -> null
            }

        if (effectType == SCORE_EFFECT_TYPE_SOURCE) {
            val sourceId = args[EVALUATOR_LEAF_SCORE_SOURCE_FIELD] as? String
                ?: (existingScoreEffect as? ScoreEffect.SourceScore)?.sourceId
            val operatorId = args[EVALUATOR_LEAF_SCORE_OPERATOR_FIELD] as? String
                ?: (existingScoreEffect as? ScoreEffect.SourceScore)?.operatorId

            val registry = org.koin.core.context.GlobalContext.get().get<lin.rule.condition.ConditionRegistry>()
            val assembler = registry.conditionAssembler
            val dataSource = sourceId?.let { assembler?.findDataSource(it) }
            if (dataSource != null) {
                builtIn.addAll(dataSource.fields)
            }

            val operator = operatorId?.let { scoreOperatorRegistry.find(it) }
            if (operator != null) {
                builtIn.addAll(operator.paramSpecs)
            }
        }

        builtIn.addAll(uiItem.fields)
        return builtIn
    }


    private fun loadLeafUiItems(): List<EvaluatorLeafMeta> {
        return leafSourceCatalog.loadAll()
    }

    private fun createLeafItemCell(): ListCell<EvaluatorLeafMeta> {
        return object : ListCell<EvaluatorLeafMeta>() {
            override fun updateItem(item: EvaluatorLeafMeta?, empty: Boolean) {
                super.updateItem(item, empty)
                text = if (empty || item == null) null
                else item.name?.takeIf { it.isNotBlank() } ?: item.sourceId
            }
        }
    }

    private fun fieldValueOf(propertyName: String, existing: EvaluatorLeafConfig?): Any? {
        return existing?.valueOfField(propertyName)
    }

    private fun updateLeafConfig(
        nodeId: String,
        selectedLeaf: EvaluatorLeafMeta?,
        existing: EvaluatorLeafConfig?,
        args: MutableMap<String, Any>
    ) {
        selectedLeaf ?: return
        val newConfig = buildEvaluatorLeafConfig(
            nodeId = nodeId,
            selectedLeaf = selectedLeaf,
            existing = existing,
            formValues = args
        )
        leafConfigs[nodeId] = newConfig
    }
}
