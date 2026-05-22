package lin.tree_config.ui

import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.Separator
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import lin.rule.tree.*
import lin.serviceLoader.provider.SelectOptionProvider
import lin.tree_config.service.EvaluatorLeafSourceCatalog
import lin.utils.serviceLoader.ServiceLoaderUtils
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * 右侧属性面板：根据选中节点类型动态渲染不同的属性表单
 */
class PropertyPanel : VBox(8.0), KoinComponent {

    private val leafSourceCatalog: EvaluatorLeafSourceCatalog by inject()

    // 把当前面板状态回传出去 (用于保存时抓取修改后的 EvaluatorLeafConfig)
    var onRuleConfigChanged: ((String, EvaluatorLeafConfig) -> Unit)? = null

    private val optionProviders: Map<String, SelectOptionProvider> by lazy {
        ServiceLoaderUtils.getCacheServices(SelectOptionProvider::class.java)
            .associateBy { it.dataSourceId }
    }
    private val dynamicFieldForm by lazy { DynamicFieldForm(optionProviders) }

    init {
        padding = Insets(10.0)
        children.add(Label("节点属性").apply {
            style = "-fx-font-weight: bold; -fx-padding: 0 0 5 0;"
        })
        showPlaceholder()
    }

    fun showPlaceholder() {
        clearContent()
        children.add(Label("请在中间树中选择一个节点...").apply {
            style = "-fx-text-fill: #888;"
        })
    }

    fun showStructureNode(nodeType: LogicNodeType) {
        clearContent()
        children.add(Label("类型: ${nodeType.name}").apply {
            style = "-fx-font-size: 14px;"
        })
        children.add(Label("结构节点，无需配置额外属性。").apply {
            style = "-fx-text-fill: #888;"
        })
    }

    fun showRuleConfigNode(
        wrapper: LogicNodeWrapper<EvaluatorPayload>,
        leafConfigs: MutableMap<String, EvaluatorLeafConfig>
    ) {
        clearContent()

        val nodeId = when (val payload = wrapper.payload) {
            is EvaluatorPayload.Rule -> payload.nodeId
            is EvaluatorPayload.BranchCondition -> payload.nodeId
            else -> return
        }
        val existing = leafConfigs[nodeId]
        val isBranch = wrapper.type == LogicNodeType.BRANCH

        children.addAll(buildHeader(nodeId, isBranch))
        children.add(Separator())

        val allLeafItems = loadLeafUiItems()
        val leafSourceCombo = createLeafSourceCombo(allLeafItems, existing)
        val dynamicFormArea = VBox(6.0)

        // 当叶子来源改变时，重绘下方动态表单
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
                    leafConfigs
                )
                updateLeafConfig(nodeId, selectedLeaf, existing, currentArgs, leafConfigs)
            }
        }

        children.addAll(
            HBox(8.0).apply {
                alignment = Pos.CENTER_LEFT
                children.addAll(Label("策略:"), leafSourceCombo)
                HBox.setHgrow(leafSourceCombo, Priority.ALWAYS)
            },
            dynamicFormArea
        )

        // 若已有配置，触发一次初始渲染
        if (existing != null) {
            val leafItem =
                allLeafItems.firstOrNull {
                    it.sourceType == existing.sourceType && it.sourceId == existing.sourceId
                }
            if (leafItem != null) {
                val currentArgs = existing.args.toMutableMap()
                buildDynamicForm(dynamicFormArea, leafItem, nodeId, leafSourceCombo, existing, currentArgs, leafConfigs)
            }
        }
    }

    private fun buildHeader(nodeId: String, isBranch: Boolean): List<Node> {
        val typeLabel = if (isBranch) "Branch 节点" else "Rule 节点"
        val nodes = mutableListOf<Node>(
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
        allLeafItems: List<EvaluatorLeafUiItem>,
        existing: EvaluatorLeafConfig?
    ): ComboBox<EvaluatorLeafUiItem> {
        return ComboBox<EvaluatorLeafUiItem>().apply {
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
        uiItem: EvaluatorLeafUiItem,
        nodeId: String,
        leafSourceCombo: ComboBox<EvaluatorLeafUiItem>,
        existing: EvaluatorLeafConfig?,
        args: MutableMap<String, Any>,
        leafConfigs: MutableMap<String, EvaluatorLeafConfig>
    ) {
        container.children.clear()

        val form = dynamicFieldForm.build(
            specs = uiItem.builtInFields + uiItem.fields,
            existingValues = { propertyName -> fieldValueOf(propertyName, existing) },
        ) { propertyName, value ->
            args[propertyName] = value
            updateLeafConfig(nodeId, leafSourceCombo.value, existing, args, leafConfigs)
        }

        container.children.add(form)
    }

    private fun loadLeafUiItems(): List<EvaluatorLeafUiItem> {
        return leafSourceCatalog.loadAll()
    }

    private fun createLeafItemCell(): ListCell<EvaluatorLeafUiItem> {
        return object : ListCell<EvaluatorLeafUiItem>() {
            override fun updateItem(item: EvaluatorLeafUiItem?, empty: Boolean) {
                super.updateItem(item, empty)
                text = if (empty || item == null) {
                    null
                } else {
                    formatLeafItemLabel(item)
                }
            }
        }
    }

    private fun formatLeafItemLabel(item: EvaluatorLeafUiItem): String {
        return item.name?.takeIf { it.isNotBlank() } ?: item.sourceId
    }

    private fun fieldValueOf(propertyName: String, existing: EvaluatorLeafConfig?): Any? {
        return existing?.valueOfField(propertyName)
    }

    private fun updateLeafConfig(
        nodeId: String,
        selectedLeaf: EvaluatorLeafUiItem?,
        existing: EvaluatorLeafConfig?,
        args: MutableMap<String, Any>,
        leafConfigs: MutableMap<String, EvaluatorLeafConfig>
    ) {
        selectedLeaf ?: return
        val newConfig = buildEvaluatorLeafConfig(
            nodeId = nodeId,
            selectedLeaf = selectedLeaf,
            existing = existing,
            formValues = args
        )
        leafConfigs[nodeId] = newConfig
        onRuleConfigChanged?.invoke(nodeId, newConfig)
    }

    private fun clearContent() {
        // 保留第一个 title Label
        if (children.size > 1) {
            children.subList(1, children.size).clear()
        }
    }
}
