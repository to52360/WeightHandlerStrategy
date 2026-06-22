package lin.tree_config.ui.strategy.editors

import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.Separator
import javafx.scene.layout.VBox
import lin.condition_tree.ui.components.OrthogonalConditionDialog
import lin.rule.score.ScoreEffect
import lin.rule.tree.CONDITION_BUILT_IN_FIELDS
import lin.rule.tree.OrthogonalConditionLeafConfig
import lin.tree_config.ui.strategy.LeafEditContext
import lin.tree_config.ui.strategy.LeafEditor
import lin.tree_config.ui.strategy.valueOfField

class OrthogonalConditionLeafEditor : LeafEditor {

    override fun render(ctx: LeafEditContext): VBox {
        val container = VBox(8.0)
        val currentConfig = ctx.leafConfigs[ctx.nodeId] as? OrthogonalConditionLeafConfig

        val btn = Button("配置正交条件详情...").apply { maxWidth = Double.MAX_VALUE }
        val currentOrtho = currentConfig?.guardCondition
        val summaryLabel = Label(
            currentOrtho?.let { "正交条件: ${it.sourceId} -> ${it.operatorId}" } ?: "未配置正交条件"
        ).apply {
            style = if (currentOrtho != null) "-fx-text-fill: #333;" else "-fx-text-fill: #888; -fx-font-style: italic;"
        }

        btn.setOnAction {
            val liveOrtho = (ctx.leafConfigs[ctx.nodeId] as? OrthogonalConditionLeafConfig)?.guardCondition
            val dialog = OrthogonalConditionDialog(liveOrtho)
            val res = dialog.showAndWait()
            if (res.isPresent) {
                val orthoRef = res.get()
                val existingScore = (ctx.leafConfigs[ctx.nodeId] as? OrthogonalConditionLeafConfig)?.scoreEffect
                    ?: ScoreEffect.ConstantScore(0.0)
                val newConfig = OrthogonalConditionLeafConfig(
                    nodeId = ctx.nodeId,
                    guardCondition = orthoRef,
                    scoreEffect = existingScore
                )
                ctx.leafConfigs[ctx.nodeId] = newConfig
                summaryLabel.text = "正交条件: ${orthoRef.sourceId} -> ${orthoRef.operatorId}"
                summaryLabel.style = "-fx-text-fill: #333;"
                ctx.onChanged()
            }
        }

        container.children.addAll(btn, summaryLabel)

        // 叶子节点额外显示 ConstantScore 表单
        if (!ctx.isBranch) {
            container.children.add(Separator())
            val specs = CONDITION_BUILT_IN_FIELDS
            val form = ctx.dynamicFieldForm.build(
                specs,
                existingValues = { prop -> ctx.leafConfigs[ctx.nodeId]?.valueOfField(prop) }
            ) { prop, value ->
                val existing = ctx.leafConfigs[ctx.nodeId] as? OrthogonalConditionLeafConfig
                val scoreEffect = existing?.scoreEffect ?: ScoreEffect.ConstantScore(0.0)
                val missVal = scoreEffect.missValue
                val updatedScore = when {
                    prop == "constantScore" && value is Number -> ScoreEffect.ConstantScore(
                        value.toDouble(), missVal
                    )

                    prop == "missValue" && value is Number -> when (scoreEffect) {
                        is ScoreEffect.ConstantScore -> scoreEffect.copy(missValue = value.toDouble())
                        is ScoreEffect.SourceScore -> scoreEffect.copy(missValue = value.toDouble())
                    }

                    else -> scoreEffect
                }
                ctx.leafConfigs[ctx.nodeId] = OrthogonalConditionLeafConfig(
                    nodeId = ctx.nodeId,
                    guardCondition = existing?.guardCondition ?: error("正交条件 guardCondition 不可为空"),
                    scoreEffect = updatedScore
                )
                ctx.onChanged()
            }
            container.children.add(form)
        }

        return container
    }
}
