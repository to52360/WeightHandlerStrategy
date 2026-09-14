package lin.ui.card_group

import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.Tooltip
import javafx.scene.layout.FlowPane
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import lin.repository.card_group.PresetDetail
import lin.repository.card_group.PresetSummary
import lin.ui.WorkbenchNavigator
import lin.ui.strategy_preset.StrategyPresetExtension
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** 下拉选项模型 */
data class PresetComboItem(
    val presetId: String?,
    val displayText: String
) {
    override fun toString() = displayText
}

/**
 * 卡组策略预设关联与状态呈现面板（T-TG-018）。
 *
 * 包含：
 * 1. 策略预设下拉选择（含"不用预设"通道）；
 * 2. 切换提示："需重启引擎装配生效"；
 * 3. 选中预设的摘要卡片（白名单树数、时序覆盖数、禁用用途摘要）；
 * 4. "跳转编辑预设"快捷通道。
 */
class CardGroupPresetPane : VBox(6.0), KoinComponent {

    private val navigator: WorkbenchNavigator by inject()

    /** 预设切换回调：返回错误信息（null = 成功），供调用方展示保存失败原因 */
    var onPresetChanged: ((presetId: String?) -> String?)? = null

    /** 操作结果提示（保存失败等），成功时隐藏 */
    private val statusLabel = Label().apply {
        style = "-fx-font-size: 11px; -fx-text-fill: #c0392b; -fx-padding: 2 0 0 0;"
        isVisible = false
        isManaged = false
    }

    private val presetComboBox = ComboBox<PresetComboItem>().apply {
        prefWidth = 280.0
    }

    private val jumpButton = Button("🔗 跳转编辑预设").apply {
        style = "-fx-font-size: 11px; -fx-background-color: #3498db; -fx-text-fill: white; -fx-cursor: hand;"
        tooltip = Tooltip("跳转到策略预设工作台并定位到此预设")
        isDisable = true
    }

    private val restartNotice = Label("⚠️ 提示：预设切换保存后，需重启引擎装配生效").apply {
        style =
            "-fx-text-fill: #d35400; -fx-font-size: 11px; -fx-background-color: #fef5e7; -fx-padding: 3 6 3 6; -fx-background-radius: 3;"
    }

    // 预设摘要卡片
    private val summaryCard = VBox(4.0).apply {
        style =
            "-fx-border-color: #e2e8f0; -fx-border-radius: 4px; -fx-background-color: #f8fafc; -fx-padding: 6 10 6 10;"
        isVisible = false
        isManaged = false
    }

    private val summaryTitle = Label().apply {
        style = "-fx-font-weight: bold; -fx-font-size: 12px; -fx-text-fill: #2c3e50;"
    }

    private val summaryDetails = Label().apply {
        style = "-fx-font-size: 11px; -fx-text-fill: #64748b;"
    }

    private val disabledTagsFlow = FlowPane(4.0, 4.0).apply {
        alignment = Pos.CENTER_LEFT
    }

    private var isUpdating = false
    private var currentSelectedPresetId: String? = null

    init {
        padding = Insets(4.0, 0.0, 4.0, 0.0)

        val selectorBox = HBox(8.0).apply {
            alignment = Pos.CENTER_LEFT
            children.addAll(
                Label("策略预设:").apply { style = "-fx-font-weight: bold; -fx-text-fill: #34495e;" },
                presetComboBox,
                jumpButton,
                restartNotice
            )
        }

        summaryCard.children.addAll(
            summaryTitle,
            summaryDetails,
            HBox(6.0, Label("🚫 禁用用途:").apply {
                style = "-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #e74c3c;"
            }, disabledTagsFlow)
        )

        children.addAll(selectorBox, statusLabel, summaryCard)

        presetComboBox.valueProperty().addListener { _, _, newValue ->
            if (!isUpdating && newValue != null) {
                currentSelectedPresetId = newValue.presetId
                jumpButton.isDisable = newValue.presetId == null
                val error = onPresetChanged?.invoke(newValue.presetId)
                if (error != null) {
                    statusLabel.text = "❌ 预设关联失败: $error"
                    statusLabel.isVisible = true
                    statusLabel.isManaged = true
                } else {
                    statusLabel.isVisible = false
                    statusLabel.isManaged = false
                }
            }
        }

        jumpButton.setOnAction {
            val targetId = currentSelectedPresetId
            if (!targetId.isNullOrBlank()) {
                navigator.navigateTo(StrategyPresetExtension.TITLE, targetId)
            }
        }
    }

    /**
     * 更新组件状态。
     */
    fun updateState(
        currentPresetId: String?,
        availablePresets: List<PresetSummary>,
        presetDetail: PresetDetail?,
        purposeUniverse: Set<String>
    ) {
        isUpdating = true
        try {
            currentSelectedPresetId = currentPresetId
            jumpButton.isDisable = currentPresetId.isNullOrBlank()

            // 状态同步时清除上一轮的操作提示
            statusLabel.isVisible = false
            statusLabel.isManaged = false

            // 构造下拉项
            val items = mutableListOf<PresetComboItem>()
            items.add(PresetComboItem(null, "（不用预设 - 全局用途树生效）"))
            for (p in availablePresets) {
                val displayText = "${p.preset.name} (${p.treeItemCount}树 / ${p.timingCount}时序)"
                items.add(PresetComboItem(p.preset.id, displayText))
            }
            presetComboBox.items.setAll(items)

            // 设置当前选中项
            val matched = items.find { it.presetId == currentPresetId } ?: items.first()
            presetComboBox.value = matched

            // 摘要卡片渲染
            if (currentPresetId.isNullOrBlank() || presetDetail == null) {
                summaryCard.isVisible = false
                summaryCard.isManaged = false
            } else {
                summaryCard.isVisible = true
                summaryCard.isManaged = true

                val preset = presetDetail.preset
                summaryTitle.text = "📋 预设信息：${preset.name} (id: ${preset.id})"
                val declaredTags = presetDetail.treeSelections.keys
                val treeCount = presetDetail.treeSelections.values.sumOf { it.size }
                val timingCount = presetDetail.timings.size
                val desc = preset.description?.takeIf { it.isNotBlank() } ?: "暂无描述"
                summaryDetails.text =
                    "描述: $desc | 已声明 ${declaredTags.size} 个用途，保留 $treeCount 棵全局树 | 时序覆盖: $timingCount 项"

                // 计算并展示禁用用途摘要
                val disabledTags = (purposeUniverse - declaredTags).sorted()
                disabledTagsFlow.children.clear()
                if (disabledTags.isEmpty()) {
                    disabledTagsFlow.children.add(Label("无（全局用途全集均已声明）").apply {
                        style = "-fx-font-size: 11px; -fx-text-fill: #27ae60;"
                    })
                } else {
                    for (tag in disabledTags) {
                        val badge = Label(tag).apply {
                            style =
                                "-fx-background-color: #fadbd8; -fx-text-fill: #c0392b; -fx-padding: 1 4 1 4; -fx-background-radius: 3; -fx-font-size: 10px;"
                        }
                        disabledTagsFlow.children.add(badge)
                    }
                }
            }
        } finally {
            isUpdating = false
        }
    }
}
