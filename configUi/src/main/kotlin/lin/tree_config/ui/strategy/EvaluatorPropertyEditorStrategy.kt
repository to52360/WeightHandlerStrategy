package lin.tree_config.ui.strategy

import javafx.geometry.Pos
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.Separator
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import lin.rule.parse.FieldSpec
import lin.rule.score.DefaultScoreOperators
import lin.rule.score.ScoreEffect
import lin.rule.tree.*
import lin.serviceLoader.provider.SelectOptionProvider
import lin.tree_config.db.EvaluatorLeafSourceCatalog
import lin.tree_config.ui.DynamicFieldForm
import lin.tree_config.ui.LogicNodeType
import lin.tree_config.ui.LogicNodeWrapper
import lin.ui.components.PropertyEditorStrategy
import lin.utils.serviceLoader.ServiceLoaderUtils

class EvaluatorPropertyEditorStrategy(
    private val leafSourceCatalog: EvaluatorLeafSourceCatalog,
    private val leafConfigs: MutableMap<String, EvaluatorLeafConfig>
) : PropertyEditorStrategy<EvaluatorPayload> {

    private val optionProviders: Map<String, SelectOptionProvider> by lazy {
        ServiceLoaderUtils.getCacheServices(SelectOptionProvider::class.java)
            .associateBy { it.dataSourceId }
    }

    private val dynamicFieldForm by lazy { DynamicFieldForm(optionProviders) }

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
        val leafSourceCombo = createLeafSourceCombo(allLeafItems, existing)
        val dynamicFormArea = VBox(6.0)

        leafSourceCombo.selectionModel.selectedItemProperty().addListener { _, _, selectedLeaf ->
            dynamicFormArea.children.clear()
            if (selectedLeaf != null) {
                val currentArgs = existing?.args?.toMutableMap() ?: mutableMapOf()
                buildDynamicForm(
                    dynamicFormArea,
                    selectedLeaf,
                    nodeId,
                    leafSourceCombo,
                    existing,
                    currentArgs,
                    isBranch
                )
                updateLeafConfig(nodeId, selectedLeaf, existing, currentArgs)
                onChanged()
            }
        }

        panel.children.addAll(
            HBox(8.0).apply {
                alignment = Pos.CENTER_LEFT
                children.addAll(Label("策略:"), leafSourceCombo)
                HBox.setHgrow(leafSourceCombo, Priority.ALWAYS)
            },
            dynamicFormArea
        )

        if (existing != null) {
            val leafItem = allLeafItems.firstOrNull {
                it.sourceType == existing.sourceType && it.sourceId == existing.sourceId
            }
            if (leafItem != null) {
                val currentArgs = existing.args.toMutableMap()
                buildDynamicForm(dynamicFormArea, leafItem, nodeId, leafSourceCombo, existing, currentArgs, isBranch)
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

    private fun createLeafSourceCombo(
        allLeafItems: List<EvaluatorLeafMeta>,
        existing: EvaluatorLeafConfig?
    ): ComboBox<EvaluatorLeafMeta> {
        return ComboBox<EvaluatorLeafMeta>().apply {
            maxWidth = Double.MAX_VALUE
            items.addAll(allLeafItems)
            setCellFactory { createLeafItemCell() }
            buttonCell = createLeafItemCell()
            existing?.let { existingConfig ->
                allLeafItems.firstOrNull {
                    it.sourceType == existingConfig.sourceType && it.sourceId == existingConfig.sourceId
                }?.let { selectionModel.select(it) }
            }
        }
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

    /**
     * 根据当前表单状态动态计算字段列表：
     * - Branch 节点只渲染条件字段（fields），不渲染评分效应相关字段
     * - 选中 source 评分效应 + 有效算子时，追加算子 paramSpecs
     * - 其它情况仅渲染基础的 builtInFields + fields
     */
    private fun computeFieldSpecs(
        uiItem: EvaluatorLeafMeta,
        args: Map<String, Any>,
        existing: EvaluatorLeafConfig?,
        isBranch: Boolean
    ): List<FieldSpec> {
        if (isBranch) return uiItem.fields

        val builtIn = uiItem.builtInFields.toMutableList()

        val effectType = args[EVALUATOR_LEAF_SCORE_EFFECT_TYPE_FIELD] as? String
            ?: when (existing?.scoreEffect) {
                is ScoreEffect.SourceScore -> SCORE_EFFECT_TYPE_SOURCE
                else -> null
            }

        if (effectType == SCORE_EFFECT_TYPE_SOURCE) {
            val operatorId = args[EVALUATOR_LEAF_SCORE_OPERATOR_FIELD] as? String
                ?: (existing?.scoreEffect as? ScoreEffect.SourceScore)?.operatorId

            val operator = operatorId?.let { DefaultScoreOperators.all[it] }
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
