package lin.ui.card_group

import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.Tooltip
import javafx.scene.layout.HBox
import lin.repository.card_group.DeckDelta
import lin.repository.card_group.Dimension

/**
 * 卡组微调层（Delta）紧凑状态呈现与弹窗触发条。
 *
 * 替代原有膨胀占用 400px 高度的折叠面板，常驻高度仅约 28px。
 * 清晰展示当前卡组是否具有专属微调（排除树数量、时序覆盖项），并提供弹窗编辑入口。
 */
class DeckDeltaBar(
    var onOpenDialog: (() -> Unit)? = null
) : HBox(8.0) {

    private val titleLabel = Label("卡组策略微调:").apply {
        style = "-fx-font-weight: bold; -fx-text-fill: #34495e; -fx-font-size: 11px;"
    }

    private val summaryLabel = Label("无专属微调 (继承预设)").apply {
        style = "-fx-font-size: 11px; -fx-text-fill: #7f8c8d;"
    }

    private val configureButton = Button("⚙️ 配置微调 (Delta)...").apply {
        style =
            "-fx-font-size: 11px; -fx-cursor: hand; -fx-background-color: #ecf0f1; -fx-border-color: #bdc3c7; -fx-border-radius: 3px;"
        setOnAction { onOpenDialog?.invoke() }
    }

    init {
        alignment = Pos.CENTER_LEFT
        padding = Insets(2.0, 0.0, 4.0, 0.0)
        children.addAll(titleLabel, summaryLabel, configureButton)
    }

    /**
     * 更新状态条展示。
     */
    fun updateState(currentItem: CardManagerItem?, delta: DeckDelta?) {
        val isDraftOrNull = currentItem == null || currentItem.isDraft
        configureButton.isDisable = isDraftOrNull
        if (isDraftOrNull) {
            configureButton.tooltip = Tooltip("请先保存卡组方案后再配置微调")
            summaryLabel.text = if (currentItem == null) "未选择卡组方案" else "方案尚未保存"
            summaryLabel.style = "-fx-font-size: 11px; -fx-text-fill: #95a5a6;"
            return
        }

        configureButton.tooltip = Tooltip("点击打开独立弹窗配置本卡组专属微调（排除树/时序/惜售/维度禁用）")

        val treeCount = delta?.treeExclusions?.values?.sumOf { it.size } ?: 0
        val timingCount = delta?.timings?.size ?: 0
        val surplusCount = delta?.surplus?.size ?: 0
        val exclusionCount = delta?.exclusionDimensions?.size ?: 0
        // 部分禁（只禁部分维度，非整体退出）的用途单独计数，便于文案表达（D-TG-021）
        val partialExclusionCount = delta?.exclusionDimensions
            ?.count { it.value != Dimension.EXCLUDABLE_DIMENSIONS } ?: 0

        if (treeCount == 0 && timingCount == 0 && surplusCount == 0 && exclusionCount == 0) {
            summaryLabel.text = "无专属微调 (继承预设)"
            summaryLabel.style = "-fx-font-size: 11px; -fx-text-fill: #7f8c8d;"
        } else {
            val parts = mutableListOf<String>()
            if (treeCount > 0) parts.add("已排除 $treeCount 棵用途树")
            if (timingCount > 0) parts.add("覆盖 $timingCount 项时序")
            if (surplusCount > 0) parts.add("覆盖 $surplusCount 项惜售")
            if (exclusionCount > 0) parts.add("不使用 $exclusionCount 个用途")
            if (partialExclusionCount > 0) parts.add("其中 $partialExclusionCount 个仅部分维度禁用")
            summaryLabel.text = "已启用专属微调: [${parts.joinToString("，")}]"
            summaryLabel.style = "-fx-font-size: 11px; -fx-text-fill: #2980b9; -fx-font-weight: bold;"
        }
    }
}
