package lin.ui.strategy_preset

import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.control.TextField
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import lin.bean.usePlan.UseStage
import lin.repository.card_group.ThresholdPatch
import lin.repository.card_group.TimingOverride
import lin.repository.card_purpose.PurposeTagRuleEntity

/**
 * 用途出牌时序覆盖面板（T-TG-017）。
 *
 * 仅针对在 purpose_tag_rule 表中有规则行的用途提供时序覆盖能力。
 * 门槛与重规划开关严格遵循 Q5 裁定，采用三态设计表达 presence 语义。
 * 供 StrategyPresetDetailPane 使用，且可供 T-TG-018 卡组增量项复用。
 */
class TimingOverridePanel(
    title: String = "⏱️ 用途出牌时序覆盖（仅有全局规则行的用途生效，未覆盖项回落全局）："
) : VBox(8.0) {

    private val scrollContent = VBox(8.0).apply {
        padding = Insets(6.0)
    }

    private val scrollPane = ScrollPane(scrollContent).apply {
        isFitToWidth = true
        prefHeight = 260.0
        style = "-fx-background-color: transparent;"
    }

    // tag -> 时序表单控件持有对象
    private val formRowMap = mutableMapOf<String, TimingFormRow>()

    init {
        padding = Insets(6.0)
        VBox.setVgrow(scrollPane, Priority.ALWAYS)
        children.addAll(
            Label(title).apply {
                style = "-fx-font-weight: bold; -fx-text-fill: #2c3e50; -fx-font-size: 12px;"
            },
            scrollPane
        )
    }

    /**
     * 加载时序规则列表与已有覆盖数据。
     */
    fun loadTimings(
        rules: List<PurposeTagRuleEntity>,
        currentTimings: Map<String, TimingOverride>
    ) {
        scrollContent.children.clear()
        formRowMap.clear()

        if (rules.isEmpty()) {
            scrollContent.children.add(Label("当前数据库中无全局用途时序规则").apply {
                style = "-fx-text-fill: #95a5a6; -fx-padding: 10px;"
            })
            return
        }

        for (rule in rules) {
            val existing = currentTimings[rule.tagId]
            val row = TimingFormRow(rule, existing)
            formRowMap[rule.tagId] = row
            scrollContent.children.add(row.container)
        }
    }

    /** 收集当前有效时序覆盖项（整体替换语义） */
    fun collectTimings(): Map<String, TimingOverride> {
        val result = mutableMapOf<String, TimingOverride>()
        for ((tag, row) in formRowMap) {
            val timing = row.toTimingOverride()
            if (!timing.isEmpty) {
                result[tag] = timing
            }
        }
        return result
    }

    /**
     * 表单校验（Q5 三态语义防呆）：
     * 「设门槛为」模式下数值必须可解析 —— 否则该输入会被静默解释为
     * `ThresholdPatch(null)`（清除为无门槛），语义完全反转，必须拦截。
     *
     * @return 首个校验错误描述；null 表示全部通过
     */
    fun validate(): String? {
        for ((tag, row) in formRowMap) {
            row.validate()?.let { return "用途 [$tag]: $it" }
        }
        return null
    }

    /** 单个用途的时序表单行控件封装 */
    private class TimingFormRow(
        val rule: PurposeTagRuleEntity,
        initial: TimingOverride?
    ) {
        val container = VBox(4.0).apply {
            style = "-fx-border-color: #e2e8f0; -fx-border-radius: 4px; -fx-background-color: white; -fx-padding: 6px;"
        }

        val comboStage = ComboBox<String>().apply {
            items.add("不覆盖 (默认: ${rule.defaultStage})")
            items.addAll(UseStage.entries.map { it.name })
            value = initial?.defaultStage ?: items.first()
            prefWidth = 150.0
        }

        val txtWeight = TextField().apply {
            promptText = "默认: ${rule.defaultOrderWeight}"
            text = initial?.defaultOrderWeight?.toString() ?: ""
            prefWidth = 80.0
        }

        // Q5: 门槛三态控件（不覆盖 / 设值 / 清除为无门槛）
        val comboThresholdMode = ComboBox<String>().apply {
            val defaultHint = rule.defaultSurplusIdleThreshold?.let { "默认: $it" } ?: "默认: 无门槛"
            items.addAll("不覆盖 ($defaultHint)", "设门槛为", "清除为无门槛")
            prefWidth = 130.0
        }

        val txtThresholdValue = TextField().apply {
            promptText = "门槛N"
            prefWidth = 60.0
        }

        // Q5: 重规划三态控件（不覆盖 / 开启 / 关闭）
        val comboReplan = ComboBox<String>().apply {
            items.addAll("不覆盖 (默认: ${rule.defaultReplanAfterUse})", "开启 (true)", "关闭 (false)")
            prefWidth = 120.0
        }

        init {
            // 初始化门槛三态
            val patch = initial?.surplusIdleThreshold
            when {
                patch == null -> {
                    comboThresholdMode.selectionModel.select(0)
                    txtThresholdValue.isDisable = true
                }

                patch.value != null -> {
                    comboThresholdMode.selectionModel.select(1)
                    txtThresholdValue.isDisable = false
                    txtThresholdValue.text = patch.value.toString()
                }

                else -> {
                    comboThresholdMode.selectionModel.select(2)
                    txtThresholdValue.isDisable = true
                }
            }

            comboThresholdMode.valueProperty().addListener { _, _, selection ->
                txtThresholdValue.isDisable = (selection != "设门槛为")
            }

            // 初始化重规划三态
            when (initial?.defaultReplanAfterUse) {
                null -> comboReplan.selectionModel.select(0)
                true -> comboReplan.selectionModel.select(1)
                false -> comboReplan.selectionModel.select(2)
            }

            val grid = GridPane().apply {
                hgap = 8.0
                vgap = 4.0
                add(Label("阶段:"), 0, 0)
                add(comboStage, 1, 0)
                add(Label("排序权重:"), 2, 0)
                add(txtWeight, 3, 0)

                val thresholdBox = HBox(4.0).apply {
                    alignment = Pos.CENTER_LEFT
                    children.addAll(comboThresholdMode, txtThresholdValue)
                }
                add(Label("余费门槛:"), 0, 1)
                add(thresholdBox, 1, 1)
                add(Label("重规划:"), 2, 1)
                add(comboReplan, 3, 1)
            }

            val header = Label("🎯 用途 [${rule.tagId}] 出牌时序覆盖").apply {
                style = "-fx-font-weight: bold; -fx-text-fill: #2980b9; -fx-font-size: 11px;"
            }

            container.children.addAll(header, grid)
        }

        /**
         * 行级校验：门槛「设值」模式数值必须有效；权重填了就必须是数字。
         * @return 错误描述；null 表示通过
         */
        fun validate(): String? {
            if (comboThresholdMode.selectionModel.selectedIndex == 1) {
                val value = txtThresholdValue.text.trim().toIntOrNull()
                    ?: return "门槛模式为「设门槛为」但数值无效（\"${txtThresholdValue.text}\"），" +
                            "如需清除门槛请改选「清除为无门槛」"
                if (value < 0) return "门槛 N 不能为负数: $value"
            }
            val weightText = txtWeight.text.trim()
            if (weightText.isNotEmpty() && weightText.toDoubleOrNull() == null) {
                return "排序权重不是有效数字: \"$weightText\""
            }
            return null
        }

        fun toTimingOverride(): TimingOverride {
            val stage = if (comboStage.selectionModel.selectedIndex > 0) comboStage.value else null
            val weight = txtWeight.text.trim().toDoubleOrNull()

            val thresholdPatch = when (comboThresholdMode.selectionModel.selectedIndex) {
                1 -> ThresholdPatch(txtThresholdValue.text.trim().toIntOrNull())
                2 -> ThresholdPatch(null) // 清除为无门槛
                else -> null // 不覆盖
            }

            val replan = when (comboReplan.selectionModel.selectedIndex) {
                1 -> true
                2 -> false
                else -> null
            }

            return TimingOverride(
                defaultStage = stage,
                defaultOrderWeight = weight,
                defaultReplanAfterUse = replan,
                surplusIdleThreshold = thresholdPatch
            )
        }
    }
}
