package lin.ui.card_group

import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.*
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import lin.repository.card_group.DeckDelta
import lin.repository.card_group.TimingOverride
import lin.repository.card_purpose.PurposeTagRuleEntity
import lin.repository.tree_config.TreeConfigEntity
import lin.ui.strategy_preset.TimingOverridePanel
import lin.ui.strategy_preset.TreeSelectionPanel

/**
 * 卡组增量项（微调层 Delta）编辑面板（T-TG-018）。
 *
 * 对应三层模型微调层：预设 > 卡组增量项 > 全局规则。
 * 包含：
 * 1. 排除用途树微调（复用 TreeSelectionPanel，勾选表示本卡组额外排除此树）；
 * 2. 用途出牌时序微调（复用 TimingOverridePanel，覆盖预设与全局规则）；
 * 3. 维度级整体替换保存通道。
 */
class DeckDeltaPanel : TitledPane() {

    /** 保存回调：返回错误信息（null = 成功），供面板展示保存失败原因 */
    var onSaveDelta: ((treeExclusions: Map<String, Collection<String>>?, timings: Map<String, TimingOverride>?) -> String?)? =
        null

    private val treeExclusionPanel = TreeSelectionPanel(
        title = "🌲 卡组排除全局用途树（勾选表示本卡组排除此树）：",
        summaryFormatter = { tags, trees -> "已针对 $tags 个用途，排除了 $trees 棵用途树" }
    )

    private val timingOverridePanel = TimingOverridePanel(
        title = "⏱️ 卡组级用途时序微调（覆盖预设及全局规则）："
    )

    private val statusLabel = Label().apply {
        style = "-fx-font-size: 11px; -fx-text-fill: #27ae60;"
    }

    /** 在状态栏展示结果（成功=绿 / 失败=红） */
    private fun showStatus(error: String?) {
        if (error == null) {
            statusLabel.text = "✅ 卡组微调项已保存"
            statusLabel.style = "-fx-font-size: 11px; -fx-text-fill: #27ae60;"
        } else {
            statusLabel.text = "❌ 保存失败: $error"
            statusLabel.style = "-fx-font-size: 11px; -fx-text-fill: #c0392b;"
        }
    }

    private val saveButton = Button("💾 保存卡组微调").apply {
        style = "-fx-font-weight: bold; -fx-background-color: #27ae60; -fx-text-fill: white; -fx-cursor: hand;"
    }

    private val resetButton = Button("🔄 重新载入").apply {
        style = "-fx-font-size: 11px; -fx-cursor: hand;"
    }

    // 缓存当前传入的数据，供重置
    private var cachedCandidates: List<TreeConfigEntity> = emptyList()
    private var cachedTimingRules: List<PurposeTagRuleEntity> = emptyList()
    private var cachedDelta: DeckDelta? = null

    init {
        text = "⚙️ 卡组策略微调 (Delta - 排除树与时序覆盖)"
        isExpanded = false // 默认折叠，节省卡组表格显示空间

        val introLabel = Label("💡 微调层：解析链「预设 > 卡组增量项 > 全局用途规则」。此处微调仅对当前卡组生效。").apply {
            style = "-fx-font-size: 11px; -fx-text-fill: #7f8c8d; -fx-padding: 0 0 4 0;"
        }

        val tabPane = TabPane().apply {
            tabClosingPolicy = TabPane.TabClosingPolicy.UNAVAILABLE
            tabs.addAll(
                Tab("🌲 排除用途树", treeExclusionPanel),
                Tab("⏱️ 时序微调覆盖", timingOverridePanel)
            )
            prefHeight = 320.0
        }
        VBox.setVgrow(tabPane, Priority.ALWAYS)

        val actionBox = HBox(10.0).apply {
            alignment = Pos.CENTER_LEFT
            padding = Insets(6.0, 0.0, 0.0, 0.0)
            children.addAll(saveButton, resetButton, statusLabel)
        }

        val contentBox = VBox(6.0).apply {
            padding = Insets(6.0)
            children.addAll(introLabel, tabPane, actionBox)
        }

        content = contentBox

        saveButton.setOnAction {
            // Q5 三态语义防呆：门槛「设值」模式数值无效时必须拦截（否则会被静默解释为清除门槛）
            val validationError = timingOverridePanel.validate()
            if (validationError != null) {
                statusLabel.text = "❌ $validationError"
                statusLabel.style = "-fx-font-size: 11px; -fx-text-fill: #c0392b;"
                return@setOnAction
            }

            val exclusions = treeExclusionPanel.collectTreeSelections()
            val timings = timingOverridePanel.collectTimings()
            val error = onSaveDelta?.invoke(exclusions, timings)
            showStatus(error)
        }

        resetButton.setOnAction {
            loadDelta(cachedCandidates, cachedTimingRules, cachedDelta)
            statusLabel.text = "已恢复至上次保存状态"
            statusLabel.style = "-fx-font-size: 11px; -fx-text-fill: #27ae60;"
        }
    }

    /**
     * 加载微调层数据。
     */
    fun loadDelta(
        candidateTrees: List<TreeConfigEntity>,
        timingRules: List<PurposeTagRuleEntity>,
        delta: DeckDelta?
    ) {
        cachedCandidates = candidateTrees
        cachedTimingRules = timingRules
        cachedDelta = delta

        val exclusions = delta?.treeExclusions ?: emptyMap()
        treeExclusionPanel.loadTrees(candidateTrees, exclusions)

        val timings = delta?.timings ?: emptyMap()
        timingOverridePanel.loadTimings(timingRules, timings)

        statusLabel.text = ""
    }
}
