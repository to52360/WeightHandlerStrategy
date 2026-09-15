package lin.ui.card_group

import javafx.event.ActionEvent
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.*
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import lin.ui.strategy_preset.TimingOverridePanel
import lin.ui.strategy_preset.TreeSelectionPanel

/**
 * 卡组增量项（微调层 Delta）编辑弹窗（T-TG-018 优化）。
 *
 * 对应三层模型微调层：预设 > 卡组增量项 > 全局规则。
 * 提供充裕的弹窗编辑空间（720x560），避免在主界面展开折叠面板导致的垂直挤压与不可用。
 */
class DeckDeltaDialog(
    private val store: WorkbenchStore
) : Dialog<Unit>() {

    private val treeExclusionPanel = TreeSelectionPanel(
        title = "🌲 卡组排除全局用途树（勾选表示本卡组额外排除此树）：",
        summaryFormatter = { tags, trees -> "已针对 $tags 个用途，排除了 $trees 棵用途树" }
    )

    private val timingOverridePanel = TimingOverridePanel(
        title = "⏱️ 卡组级用途时序微调（覆盖预设及全局规则）："
    )

    private val errorLabel = Label().apply {
        style = "-fx-font-size: 11px; -fx-text-fill: #c0392b; -fx-font-weight: bold;"
        isVisible = false
        isManaged = false
    }

    private val resetBtn = Button("🔄 恢复为已保存状态").apply {
        style = "-fx-font-size: 11px; -fx-cursor: hand;"
    }

    init {
        val currentItem = store.state.selectedManagerItem
        val managerName = currentItem?.entity?.name?.ifBlank { "未命名方案" } ?: "未命名方案"
        title = "⚙️ 卡组策略微调 (Delta) - [$managerName]"
        headerText = "卡组增量项配置（解析链：预设 > 卡组增量项 > 全局规则）。此处的排除与覆盖仅对当前卡组生效。"

        val dialogPane = this.dialogPane
        dialogPane.prefWidth = 720.0
        dialogPane.prefHeight = 560.0
        dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)

        val okBtn = dialogPane.lookupButton(ButtonType.OK) as? Button
        okBtn?.text = "💾 保存微调"
        okBtn?.style = "-fx-font-weight: bold; -fx-background-color: #27ae60; -fx-text-fill: white; -fx-cursor: hand;"

        // 载入数据
        fun reloadData() {
            val candidateTrees = store.state.candidateTrees
            val timingRules = store.state.timingRules
            val delta = store.state.currentDeckDelta

            treeExclusionPanel.loadTrees(candidateTrees, delta?.treeExclusions ?: emptyMap())
            timingOverridePanel.loadTimings(timingRules, delta?.timings ?: emptyMap())
            errorLabel.isVisible = false
            errorLabel.isManaged = false
        }

        reloadData()

        resetBtn.setOnAction {
            reloadData()
        }

        val tabPane = TabPane().apply {
            tabClosingPolicy = TabPane.TabClosingPolicy.UNAVAILABLE
            tabs.addAll(
                Tab("🌲 排除用途树", treeExclusionPanel),
                Tab("⏱️ 时序微调覆盖", timingOverridePanel)
            )
        }
        VBox.setVgrow(tabPane, Priority.ALWAYS)

        val toolBar = HBox(10.0).apply {
            alignment = Pos.CENTER_LEFT
            padding = Insets(4.0, 0.0, 0.0, 0.0)
            children.addAll(resetBtn, errorLabel)
        }

        val contentBox = VBox(8.0).apply {
            padding = Insets(10.0)
            children.addAll(tabPane, toolBar)
        }

        dialogPane.content = contentBox

        // 提交与校验拦截
        okBtn?.addEventFilter(ActionEvent.ACTION) { event ->
            // Q5 三态语义防呆：门槛「设值」模式数值无效时必须拦截
            val validationError = timingOverridePanel.validate()
            if (validationError != null) {
                errorLabel.text = "❌ $validationError"
                errorLabel.isVisible = true
                errorLabel.isManaged = true
                event.consume()
                return@addEventFilter
            }

            val exclusions = treeExclusionPanel.collectTreeSelections()
            val timings = timingOverridePanel.collectTimings()
            val saveError = store.saveDeckDelta(exclusions, timings)
            if (saveError != null) {
                errorLabel.text = "❌ 保存失败: $saveError"
                errorLabel.isVisible = true
                errorLabel.isManaged = true
                event.consume()
                return@addEventFilter
            }
        }
    }
}
