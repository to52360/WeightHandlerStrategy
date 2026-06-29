package lin.ui.tree_config.ui.strategy.editors

import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.Separator
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import lin.rule.condition.ConditionPayload
import lin.rule.parse.FieldSpec
import lin.rule.score.ScoreEffect
import lin.rule.tree.*
import lin.ui.condition_tree.ui.components.OrthogonalConditionDialog
import lin.ui.tree_config.bridge.buildEvaluatorLeafConfig
import lin.ui.tree_config.ui.strategy.*

class DynamicFormLeafEditor : LeafEditor {

    override fun render(ctx: LeafEditContext): VBox {
        val container = VBox(8.0)
        val existing = ctx.leafConfigs[ctx.nodeId]
        val args = existing?.args?.toMutableMap() ?: mutableMapOf()

        buildDynamicForm(container, ctx, existing, args)

        // 仅 Rule 类型节点显示外挂守卫区（Condition 类型通过 sourceId 兜底，无需守卫）
        if (ctx.selectedLeaf.kind is EvaluatorLeafKind.Rule) {
            container.children.add(Separator())
            val guardBox = HBox(8.0).apply { alignment = Pos.CENTER_LEFT }
            val currentGuard =
                (ctx.leafConfigs[ctx.nodeId] as? Guarded)?.guardCondition as? ConditionPayload.PipelineRef
            val guardSummary = Label(
                currentGuard?.let { "守卫: ${it.sourceId} -> ${it.operatorId}" } ?: "无通用守卫"
            ).apply {
                style =
                    if (currentGuard != null) "-fx-text-fill: #333;" else "-fx-text-fill: #888; -fx-font-style: italic;"
            }

            val configGuardBtn = Button("配置通用守卫...")
            val clearGuardBtn = Button("清除").apply { isDisable = (currentGuard == null) }

            configGuardBtn.setOnAction {
                val liveGuard =
                    (ctx.leafConfigs[ctx.nodeId] as? Guarded)?.guardCondition as? ConditionPayload.PipelineRef
                val dialog = OrthogonalConditionDialog(liveGuard)
                val res = dialog.showAndWait()
                if (res.isPresent) {
                    val guardRef = res.get()
                    val oldConfig = ctx.leafConfigs[ctx.nodeId] ?: buildEvaluatorLeafConfig(
                        ctx.nodeId, ctx.selectedLeaf, null, ScoreEffect.ConstantScore(0.0), emptyMap()
                    )
                    val newConfig = when (oldConfig) {
                        is RuleLeafConfig -> oldConfig.copy(guardCondition = guardRef)
                        is OrthogonalRuleLeafConfig -> oldConfig.copy(guardCondition = guardRef)
                        is ConditionTreeLeafConfig -> oldConfig
                        is OrthogonalConditionLeafConfig -> oldConfig
                        else -> oldConfig
                    }
                    ctx.leafConfigs[ctx.nodeId] = newConfig
                    guardSummary.text = "守卫: ${guardRef.sourceId} -> ${guardRef.operatorId}"
                    guardSummary.style = "-fx-text-fill: #333;"
                    clearGuardBtn.isDisable = false
                    ctx.onChanged()
                }
            }

            clearGuardBtn.setOnAction {
                val oldConfig = ctx.leafConfigs[ctx.nodeId] ?: buildEvaluatorLeafConfig(
                    ctx.nodeId, ctx.selectedLeaf, null, ScoreEffect.ConstantScore(0.0), emptyMap()
                )
                val newConfig = when (oldConfig) {
                    is RuleLeafConfig -> oldConfig.copy(guardCondition = null)
                    is OrthogonalRuleLeafConfig -> oldConfig.copy(guardCondition = null)
                    is ConditionTreeLeafConfig -> oldConfig
                    is OrthogonalConditionLeafConfig -> oldConfig
                    else -> oldConfig
                }
                ctx.leafConfigs[ctx.nodeId] = newConfig
                guardSummary.text = "无通用守卫"
                guardSummary.style = "-fx-text-fill: #888; -fx-font-style: italic;"
                clearGuardBtn.isDisable = true
                ctx.onChanged()
            }

            guardBox.children.addAll(Label("外挂守卫:"), configGuardBtn, clearGuardBtn, guardSummary)
            container.children.add(guardBox)
        }

        return container
    }

    private fun buildDynamicForm(
        container: VBox,
        ctx: LeafEditContext,
        existing: EvaluatorLeafConfig?,
        args: MutableMap<String, Any>
    ) {
        container.children.clear()

        val specs = computeFieldSpecs(ctx, args, existing)
        val form = ctx.dynamicFieldForm.build(
            specs = specs,
            existingValues = { propertyName -> existing?.valueOfField(propertyName) }
        ) { propertyName, value ->
            args[propertyName] = value
            updateLeafConfig(ctx, args)

            if (propertyName == EVALUATOR_LEAF_SCORE_OPERATOR_FIELD ||
                propertyName == EVALUATOR_LEAF_SCORE_EFFECT_TYPE_FIELD
            ) {
                val updatedExisting = ctx.leafConfigs[ctx.nodeId]
                buildDynamicForm(container, ctx, updatedExisting, mutableMapOf())
            }
        }

        container.children.add(form)
    }

    private fun computeFieldSpecs(
        ctx: LeafEditContext,
        args: Map<String, Any>,
        existing: EvaluatorLeafConfig?
    ): List<FieldSpec> {
        if (ctx.isBranch) return ctx.selectedLeaf.fields

        val builtIn = ctx.selectedLeaf.builtInFields.toMutableList()

        val existingScoreEffect = (existing as? Scoreable)?.scoreEffect

        val effectType = args[EVALUATOR_LEAF_SCORE_EFFECT_TYPE_FIELD] as? String
            ?: when (existingScoreEffect) {
                is ScoreEffect.SourceScore -> "source"
                else -> null
            }

        if (effectType == "source") {
            val sourceId = args[EVALUATOR_LEAF_SCORE_SOURCE_FIELD] as? String
                ?: (existingScoreEffect as? ScoreEffect.SourceScore)?.sourceId
            val operatorId = args[EVALUATOR_LEAF_SCORE_OPERATOR_FIELD] as? String
                ?: (existingScoreEffect as? ScoreEffect.SourceScore)?.operatorId

            val operator = operatorId?.let { ctx.scoreOperatorRegistry.find(it) }
            if (operator != null) {
                builtIn.addAll(operator.paramSpecs)
            }
        }

        builtIn.addAll(ctx.selectedLeaf.fields)
        return builtIn
    }

    private fun updateLeafConfig(ctx: LeafEditContext, args: MutableMap<String, Any>) {
        val existing = ctx.leafConfigs[ctx.nodeId]
        val existingScoreEffect = (existing as? Scoreable)?.scoreEffect
        val scoreEffect = buildScoreEffect(args, existingScoreEffect, ctx.scoreOperatorRegistry)
            ?: ScoreEffect.ConstantScore(0.0)
        val extArgs = computeExtArgs(ctx.selectedLeaf, args, scoreEffect, ctx.scoreOperatorRegistry)
        val newConfig = buildEvaluatorLeafConfig(
            nodeId = ctx.nodeId,
            selectedLeaf = ctx.selectedLeaf,
            existing = existing,
            scoreEffect = scoreEffect,
            extArgs = extArgs
        )
        ctx.leafConfigs[ctx.nodeId] = newConfig
    }
}
