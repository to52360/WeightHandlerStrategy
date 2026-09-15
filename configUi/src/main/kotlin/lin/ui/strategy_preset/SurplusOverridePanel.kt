package lin.ui.strategy_preset

import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.control.TextField
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import lin.repository.card_group.SurplusOverride
import lin.repository.card_group.ThresholdPatch
import lin.repository.card_purpose.PurposeTagRuleEntity

/**
 * 用途**惜售门槛**声明面板（T-TG-029 / T-TG-030）。
 *
 * 为什么与时序面板分开（T-TG-029）：门槛 N 的语义是「**够不够余费才垫**」（惜售），
 * 与「**何时出**」（阶段 / 次序 / 重规划）是两件事 —— 此前挤在同一行里，
 * "只想调惜售"必须动时序声明，且"填了门槛"会顺带把该用途从"未声明"变成"声明"。
 *
 * 候选用途 = 全局 `purpose_tag_rule` 有规则行的用途（同时充当默认值提示来源）。
 * 三态（不声明 / 声明为 N / 声明为「不设门槛」）由下拉表达 presence 语义；
 * **某用途只要在任一面板填了值 = 声明该用途**（该用途在本层产生一条完整规则）。
 */
class SurplusOverridePanel(
    title: String = "💰 用途惜售门槛声明（填任一字段 = 声明该用途；全部留空 = 未声明）："
) : VBox(8.0) {

    private val scrollContent = VBox(8.0).apply {
        padding = Insets(6.0)
    }

    private val scrollPane = ScrollPane(scrollContent).apply {
        isFitToWidth = true
        prefHeight = 220.0
        style = "-fx-background-color: transparent;"
    }

    private val formRowMap = mutableMapOf<String, SurplusFormRow>()

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

    /** 加载规则列表与已有声明（整体替换语义的输入侧）。 */
    fun loadSurplus(
        rules: List<PurposeTagRuleEntity>,
        currentSurplus: Map<String, SurplusOverride>
    ) {
        scrollContent.children.clear()
        formRowMap.clear()

        if (rules.isEmpty()) {
            scrollContent.children.add(Label("当前数据库中无全局用途规则（无可声明惜售的用途）").apply {
                style = "-fx-text-fill: #95a5a6; -fx-padding: 10px;"
            })
            return
        }

        for (rule in rules) {
            val existing = currentSurplus[rule.tagId]
            val row = SurplusFormRow(rule, existing)
            formRowMap[rule.tagId] = row
            scrollContent.children.add(row.container)
        }
    }

    /** 收集当前有效的惜售声明项（整体替换语义）。 */
    fun collectSurplus(): Map<String, SurplusOverride> {
        val result = mutableMapOf<String, SurplusOverride>()
        for ((tag, row) in formRowMap) {
            val override = row.toSurplusOverride()
            if (!override.isEmpty) {
                result[tag] = override
            }
        }
        return result
    }

    /**
     * 表单校验：「设门槛为」模式下数值必须可解析且为正整数 —— 否则该输入会被静默解释为
     * `ThresholdPatch(null)`（声明为无门槛），语义完全反转，必须拦截。
     *
     * @return 首个校验错误描述；null 表示全部通过
     */
    fun validate(): String? {
        for ((tag, row) in formRowMap) {
            row.validate()?.let { return "用途 [$tag]: $it" }
        }
        return null
    }

    /** 单个用途的惜售表单行控件封装。 */
    private class SurplusFormRow(
        val rule: PurposeTagRuleEntity,
        initial: SurplusOverride?
    ) {
        val container = VBox(4.0).apply {
            style = "-fx-border-color: #e2e8f0; -fx-border-radius: 4px; -fx-background-color: white; -fx-padding: 6px;"
        }

        private val txtThresholdValue = TextField().apply {
            promptText = "门槛N"
            prefWidth = 60.0
        }

        private val comboThresholdMode = ComboBox<String>().apply {
            val defaultHint = rule.defaultSurplusIdleThreshold?.let { "默认: $it" } ?: "默认: 无门槛"
            items.addAll("不声明 ($defaultHint)", "声明为", "声明为无门槛")
            prefWidth = 150.0
        }

        init {
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
                txtThresholdValue.isDisable = (selection != "声明为")
            }

            val row = HBox(6.0).apply {
                alignment = Pos.CENTER_LEFT
                children.addAll(comboThresholdMode, txtThresholdValue)
            }

            val header = Label("🎯 用途 [${rule.tagId}] 惜售门槛").apply {
                style = "-fx-font-weight: bold; -fx-text-fill: #8e44ad; -fx-font-size: 11px;"
            }

            container.children.addAll(header, row)
        }

        fun validate(): String? {
            if (comboThresholdMode.selectionModel.selectedIndex != 1) return null
            val value = txtThresholdValue.text.trim().toIntOrNull()
                ?: return "门槛模式为「声明为」但数值无效（\"${txtThresholdValue.text}\"），" +
                        "如需声明为无门槛请改选「声明为无门槛」"
            if (value < 1) return "门槛 N 必须为正整数: $value"
            return null
        }

        fun toSurplusOverride(): SurplusOverride {
            val patch = when (comboThresholdMode.selectionModel.selectedIndex) {
                1 -> ThresholdPatch(txtThresholdValue.text.trim().toIntOrNull())
                2 -> ThresholdPatch(null) // 声明为无门槛
                else -> null              // 不声明
            }
            return SurplusOverride(surplusIdleThreshold = patch)
        }
    }
}
