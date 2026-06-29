package lin.ui.tree_config.ui.strategy

import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.Separator
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import lin.rule.score.ScoreEffect
import lin.rule.score.ScoreOperatorRegistry
import lin.rule.tree.*
import lin.ui.components.PropertyEditorStrategy
import lin.ui.tree_config.bridge.buildEvaluatorLeafConfig
import lin.ui.tree_config.bridge.leafKind
import lin.ui.tree_config.bridge.withGuardMissBehavior
import lin.ui.tree_config.db.EvaluatorLeafSourceCatalog
import lin.ui.tree_config.ui.DynamicFieldForm
import lin.ui.tree_config.ui.LogicNodeType
import lin.ui.tree_config.ui.LogicNodeWrapper
import lin.ui.tree_config.ui.strategy.editors.DynamicFormLeafEditor
import lin.ui.tree_config.ui.strategy.editors.OrthogonalConditionLeafEditor
import lin.ui.tree_config.ui.strategy.editors.OrthogonalRuleLeafEditor
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class EvaluatorPropertyEditorStrategy(
    private val leafConfigs: MutableMap<String, EvaluatorLeafConfig>
) : PropertyEditorStrategy<EvaluatorPayload>, KoinComponent {

    private val leafSourceCatalog: EvaluatorLeafSourceCatalog by inject()
    private val scoreOperatorRegistry: ScoreOperatorRegistry by inject()

    private val dynamicFieldForm by lazy { DynamicFieldForm() }

    private val editorRegistry = LeafEditorRegistry().apply {
        register(EvaluatorLeafKind.Condition.Orthogonal, OrthogonalConditionLeafEditor())
        register(EvaluatorLeafKind.Rule.Orthogonal, OrthogonalRuleLeafEditor())
        val dynamicEditor = DynamicFormLeafEditor()
        register(EvaluatorLeafKind.Rule.Coded, dynamicEditor)
        register(EvaluatorLeafKind.Condition.Plain, dynamicEditor)
        register(EvaluatorLeafKind.Condition.Tree, dynamicEditor)
    }

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

        val categoryCombo = ComboBox<EvaluatorLeafCategory>().apply {
            maxWidth = Double.MAX_VALUE
            items.addAll(
                if (isBranch) listOf(EvaluatorLeafCategory.CONDITION)
                else EvaluatorLeafCategory.entries.toList()
            )
            setCellFactory { createCategoryCell() }
            buttonCell = createCategoryCell()
        }

        val leafSourceCombo = ComboBox<EvaluatorLeafMeta>().apply {
            maxWidth = Double.MAX_VALUE
            setCellFactory { createLeafItemCell() }
            buttonCell = createLeafItemCell()
        }

        // 联动逻辑：切换分类时重新填充具体项
        categoryCombo.selectionModel.selectedItemProperty().addListener { _, _, newCategory ->
            leafSourceCombo.items.clear()
            if (newCategory != null) {
                val filtered = allLeafItems.filter { it.kind.category == newCategory }
                leafSourceCombo.items.addAll(filtered)
                val toSelect = if (existing != null && existing.leafKind.category == newCategory) {
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

        // 初始化选中分类
        val initialCategory = existing?.leafKind?.category
            ?: if (isBranch) EvaluatorLeafCategory.CONDITION else EvaluatorLeafCategory.RULE
        categoryCombo.selectionModel.select(initialCategory)

        val dynamicFormArea = VBox(8.0)

        // 联动渲染函数（通过编辑器注册表分发）
        fun renderEditor(selectedLeaf: EvaluatorLeafMeta) {
            dynamicFormArea.children.clear()
            val ctx = LeafEditContext(
                nodeId = nodeId,
                leafConfigs = leafConfigs,
                selectedLeaf = selectedLeaf,
                isBranch = isBranch,
                onChanged = onChanged,
                dynamicFieldForm = dynamicFieldForm,
                scoreOperatorRegistry = scoreOperatorRegistry
            )
            try {
                val editor = editorRegistry.editorFor(selectedLeaf.kind)
                dynamicFormArea.children.add(editor.render(ctx))
            } catch (e: Exception) {
                dynamicFormArea.children.add(Label("编辑器不可用: ${e.message}").apply {
                    style = "-fx-text-fill: red;"
                })
            }

            // 非 Branch 节点添加守卫未命中行为选择器
            if (!isBranch) {
                dynamicFormArea.children.add(Separator())
                dynamicFormArea.children.add(
                    buildGuardMissBehaviorRow(nodeId, leafConfigs, onChanged)
                )
            }
        }

        // leafSourceCombo 切换监听
        leafSourceCombo.selectionModel.selectedItemProperty().addListener { _, _, selectedLeaf ->
            if (selectedLeaf != null) {
                try {
                    val args = leafConfigs[nodeId]?.args?.toMutableMap() ?: mutableMapOf()
                    updateLeafConfig(nodeId, selectedLeaf, leafConfigs[nodeId], args)
                } catch (_: Exception) {
                    // 正交条件等需要用户额外配置 PipelineRef 的类型，
                    // 由编辑器（如 OrthogonalConditionLeafEditor）接管初始构建
                }
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

    // ===== private helpers =====

    private fun createCategoryCell(): ListCell<EvaluatorLeafCategory> {
        return object : ListCell<EvaluatorLeafCategory>() {
            override fun updateItem(item: EvaluatorLeafCategory?, empty: Boolean) {
                super.updateItem(item, empty)
                text = if (empty || item == null) null
                else when (item) {
                    EvaluatorLeafCategory.CONDITION -> "条件"
                    EvaluatorLeafCategory.RULE -> "规则"
                }
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

    private fun loadLeafUiItems(): List<EvaluatorLeafMeta> {
        return leafSourceCatalog.loadAll()
    }

    private fun createLeafItemCell(): ListCell<EvaluatorLeafMeta> {
        return object : ListCell<EvaluatorLeafMeta>() {
            override fun updateItem(item: EvaluatorLeafMeta?, empty: Boolean) {
                super.updateItem(item, empty)
                text = if (empty || item == null) null
                else item.name.ifBlank { item.sourceId }
            }
        }
    }

    private fun buildGuardMissBehaviorRow(
        nodeId: String,
        leafConfigs: MutableMap<String, EvaluatorLeafConfig>,
        onChanged: () -> Unit
    ): HBox {
        val behaviorCombo = ComboBox<GuardMissBehavior>().apply {
            maxWidth = Double.MAX_VALUE
            items.addAll(GuardMissBehavior.entries)
            setCellFactory { createGuardMissBehaviorCell() }
            buttonCell = createGuardMissBehaviorCell()
        }

        val currentBehavior = leafConfigs[nodeId]?.guardMissBehavior ?: GuardMissBehavior.SCORE
        behaviorCombo.selectionModel.select(currentBehavior)

        behaviorCombo.selectionModel.selectedItemProperty().addListener { _, _, newValue ->
            if (newValue != null) {
                updateGuardMissBehavior(nodeId, newValue, leafConfigs)
                onChanged()
            }
        }

        return HBox(8.0).apply {
            alignment = Pos.CENTER_LEFT
            children.addAll(Label("条件不匹配时:"), behaviorCombo)
            HBox.setHgrow(behaviorCombo, Priority.ALWAYS)
        }
    }

    private fun updateGuardMissBehavior(
        nodeId: String,
        behavior: GuardMissBehavior,
        leafConfigs: MutableMap<String, EvaluatorLeafConfig>
    ) {
        val existing = leafConfigs[nodeId] ?: return
        leafConfigs[nodeId] = existing.withGuardMissBehavior(behavior)
    }

    private fun createGuardMissBehaviorCell(): ListCell<GuardMissBehavior> {
        return object : ListCell<GuardMissBehavior>() {
            override fun updateItem(item: GuardMissBehavior?, empty: Boolean) {
                super.updateItem(item, empty)
                text = if (empty || item == null) null
                else when (item) {
                    GuardMissBehavior.SCORE -> "给兜底分 (继续评估)"
                    GuardMissBehavior.PRUNE -> "剪枝 (终止评估树)"
                }
            }
        }
    }

    private fun updateLeafConfig(
        nodeId: String,
        selectedLeaf: EvaluatorLeafMeta?,
        existing: EvaluatorLeafConfig?,
        args: MutableMap<String, Any>
    ) {
        selectedLeaf ?: return
        val existingScoreEffect = (existing as? Scoreable)?.scoreEffect
        val scoreEffect = buildScoreEffect(args, existingScoreEffect, scoreOperatorRegistry)
            ?: ScoreEffect.ConstantScore(0.0)
        val extArgs = computeExtArgs(selectedLeaf, args, scoreEffect, scoreOperatorRegistry)
        val newConfig = buildEvaluatorLeafConfig(
            nodeId = nodeId,
            selectedLeaf = selectedLeaf,
            existing = existing,
            scoreEffect = scoreEffect,
            extArgs = extArgs
        )
        leafConfigs[nodeId] = newConfig
    }

}
