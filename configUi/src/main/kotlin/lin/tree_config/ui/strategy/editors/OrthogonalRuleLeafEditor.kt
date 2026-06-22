package lin.tree_config.ui.strategy.editors

import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.layout.VBox
import lin.rule.tree.OrthogonalRuleLeafConfig
import lin.tree_config.ui.components.OrthogonalRuleDialog
import lin.tree_config.ui.strategy.LeafEditContext
import lin.tree_config.ui.strategy.LeafEditor

class OrthogonalRuleLeafEditor : LeafEditor {

    override fun render(ctx: LeafEditContext): VBox {
        val container = VBox(8.0)
        val currentConfig = ctx.leafConfigs[ctx.nodeId] as? OrthogonalRuleLeafConfig

        val btn = Button("编辑正交规则配置...").apply { maxWidth = Double.MAX_VALUE }
        val summaryLabel = Label(
            currentConfig?.scoreEffect?.let { "已配置评分，守卫: ${if (currentConfig.guardCondition != null) "有" else "无"}" }
                ?: "未配置正交规则"
        ).apply {
            style =
                if (currentConfig?.scoreEffect != null) "-fx-text-fill: #333;" else "-fx-text-fill: #888; -fx-font-style: italic;"
        }

        btn.setOnAction {
            val dialog = OrthogonalRuleDialog(currentConfig)
            val res = dialog.showAndWait()
            if (res.isPresent) {
                val savedConfig = res.get().copy(nodeId = ctx.nodeId)
                ctx.leafConfigs[ctx.nodeId] = savedConfig
                summaryLabel.text = "已配置评分，守卫: ${if (savedConfig.guardCondition != null) "有" else "无"}"
                summaryLabel.style = "-fx-text-fill: #333;"
                ctx.onChanged()
            }
        }

        container.children.addAll(btn, summaryLabel)
        return container
    }
}
