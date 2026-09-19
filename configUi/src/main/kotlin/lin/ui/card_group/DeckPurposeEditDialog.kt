package lin.ui.card_group

import javafx.event.ActionEvent
import javafx.geometry.Insets
import javafx.scene.Node
import javafx.scene.control.*
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import lin.bean.usePlan.UseStage
import lin.repository.card_group.Dimension
import lin.repository.card_group.TimingOverride
import lin.repository.tree_config.TreeConfigEntity

/**
 * 单个用途的**卡组微调**编辑弹窗（消费侧 = 覆盖语义与维度级禁用，T-TG-043 / D-TG-021）。
 *
 * 一个用途的三件事（树白名单微调 / 时序覆盖 / 惜售覆盖）在**同一个弹窗里一次配完**，不切页签；
 * 每个维度升级为**三选一**单选组（同构扩展，D-TG-021）：
 * 1. 继承预设：直接继承预设声明或默认值；
 * 2. 覆盖设置：给出具体卡组专属微调值（排除树 / 自定义时序 / 自定义门槛）；
 * 3. 🚫 禁用该维度：该用途在当前维度不生效（树不输出 / 规则不生效 / 门槛不设）。
 *
 * ⚠️ 「本卡组不使用该用途」（用途级整用途退出，D-TG-020）为表格行首的开关。
 */
class DeckPurposeEditDialog(
    private val initial: DeckPurposeDraft,
    candidateTrees: List<TreeConfigEntity>,
    displayName: String = initial.tagId
) : Dialog<DeckPurposeDraft>() {

    // ── 🌲 树维度单选组 ──
    private val tgTree = ToggleGroup()
    private val rbTreeInherit = RadioButton("继承预设").apply {
        toggleGroup = tgTree
        style = "-fx-font-size: 11px;"
    }
    private val rbTreeOverride = RadioButton("覆盖 (额外排除部分树)").apply {
        toggleGroup = tgTree
        style = "-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #2980b9;"
    }
    private val rbTreeDisable = RadioButton("禁用树维度 (本用途所有用途树不生效)").apply {
        toggleGroup = tgTree
        style = "-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #c0392b;"
    }
    private val treeChecks = mutableMapOf<String, CheckBox>()

    // ── 时序维度单选组 ──
    private val tgTiming = ToggleGroup()
    private val rbTimingInherit = RadioButton("继承预设").apply {
        toggleGroup = tgTiming
        style = "-fx-font-size: 11px;"
    }
    private val rbTimingOverride = RadioButton("覆盖 (自定义时序)").apply {
        toggleGroup = tgTiming
        style = "-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #2980b9;"
    }
    private val rbTimingDisable = RadioButton("禁用时序维度 (不产生时序规则)").apply {
        toggleGroup = tgTiming
        style = "-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #c0392b;"
    }
    private val comboStage = ComboBox<String>().apply {
        items.add("继承 (${initial.rule.defaultStage})")
        items.addAll(UseStage.entries.map { it.name })
        prefWidth = 170.0
    }
    private val txtWeight = TextField().apply {
        promptText = "继承 (${initial.rule.defaultOrderWeight})"
        prefWidth = 120.0
    }
    private val comboReplan = ComboBox<String>().apply {
        items.addAll("继承 (${initial.rule.defaultReplanAfterUse})", "覆盖为 true", "覆盖为 false")
        prefWidth = 170.0
    }
    private val txtPriority = TextField().apply {
        promptText = "继承 (${initial.rule.priority})"
        prefWidth = 90.0
    }

    // ── 惜售维度单选组 ──
    private val tgSurplus = ToggleGroup()
    private val rbSurplusInherit = RadioButton("继承预设").apply {
        toggleGroup = tgSurplus
        style = "-fx-font-size: 11px;"
    }
    private val rbSurplusOverride = RadioButton("覆盖 (自定义门槛)").apply {
        toggleGroup = tgSurplus
        style = "-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #2980b9;"
    }
    private val rbSurplusDisable = RadioButton("禁用惜售维度 (不声明门槛)").apply {
        toggleGroup = tgSurplus
        style = "-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #c0392b;"
    }
    private val chkThreshold = CheckBox("设余费门槛 N")
    private val txtThreshold = TextField().apply {
        promptText = "正整数"
        prefWidth = 70.0
    }

    private val errorLabel = Label().apply {
        style = "-fx-font-size: 11px; -fx-text-fill: #c0392b; -fx-font-weight: bold;"
        isVisible = false
        isManaged = false
    }

    init {
        title = "卡组微调 - [$displayName]"
        headerText = "用途 [$displayName] 的卡组级微调（每维各自三选一：继承预设 / 覆盖 / 禁用维度）"

        val dialogPane = this.dialogPane
        dialogPane.prefWidth = 660.0
        dialogPane.prefHeight = 560.0
        dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)

        prefill(candidateTrees)
        wireEnablement()
        dialogPane.content = buildContent()

        val okBtn = dialogPane.lookupButton(ButtonType.OK) as? Button
        okBtn?.text = "应用微调"
        okBtn?.addEventFilter(ActionEvent.ACTION) { event ->
            validate()?.let { message ->
                errorLabel.text = message
                errorLabel.isVisible = true
                errorLabel.isManaged = true
                event.consume()
            }
        }

        setResultConverter { button ->
            if (button == ButtonType.OK) buildDraft() else null
        }
    }

    private fun prefill(candidateTrees: List<TreeConfigEntity>) {
        candidateTrees.forEach { tree ->
            val cb = CheckBox("${tree.name} [id: ${tree.id}]" + if (tree.enabled) "" else " (已禁用)").apply {
                isSelected = tree.id in initial.excludedTrees
                if (!tree.enabled) style = "-fx-text-fill: #95a5a6;"
            }
            treeChecks[tree.id] = cb
        }

        // 树维度状态回填
        when {
            Dimension.PURPOSE_TREE in initial.excludedDimensions -> rbTreeDisable.isSelected = true
            initial.excludedTrees.isNotEmpty() -> rbTreeOverride.isSelected = true
            else -> rbTreeInherit.isSelected = true
        }

        // 时序维度状态回填
        when {
            Dimension.PURPOSE_TIMING in initial.excludedDimensions -> rbTimingDisable.isSelected = true
            initial.overrideTiming -> rbTimingOverride.isSelected = true
            else -> rbTimingInherit.isSelected = true
        }
        comboStage.value = initial.timing.defaultStage ?: comboStage.items.first()
        txtWeight.text = initial.timing.defaultOrderWeight?.toString() ?: ""
        comboReplan.selectionModel.select(
            when (initial.timing.defaultReplanAfterUse) {
                null -> 0
                true -> 1
                false -> 2
            }
        )
        txtPriority.text = initial.timing.priority?.toString() ?: ""

        // 惜售维度状态回填
        when {
            Dimension.PURPOSE_SURPLUS in initial.excludedDimensions -> rbSurplusDisable.isSelected = true
            initial.overrideSurplus -> rbSurplusOverride.isSelected = true
            else -> rbSurplusInherit.isSelected = true
        }
        chkThreshold.isSelected = initial.surplusThreshold != null
        txtThreshold.text = initial.surplusThreshold?.toString() ?: ""
    }

    private fun wireEnablement() {
        treeChecks.values.forEach { it.disableProperty().bind(rbTreeOverride.selectedProperty().not()) }
        listOf(comboStage, txtWeight, comboReplan, txtPriority).forEach {
            it.disableProperty().bind(rbTimingOverride.selectedProperty().not())
        }
        chkThreshold.disableProperty().bind(rbSurplusOverride.selectedProperty().not())
        txtThreshold.disableProperty().bind(
            rbSurplusOverride.selectedProperty().and(chkThreshold.selectedProperty()).not()
        )
    }

    private fun buildContent(): VBox {
        val treeBody = VBox(3.0).apply {
            padding = Insets(0.0, 0.0, 0.0, 20.0)
            if (treeChecks.isEmpty()) {
                children.add(Label("该用途暂无全局用途树").apply {
                    style = "-fx-text-fill: #95a5a6; -fx-font-size: 11px;"
                })
            } else {
                children.addAll(treeChecks.values)
            }
        }

        val timingBody = GridPane().apply {
            hgap = 8.0
            vgap = 6.0
            padding = Insets(0.0, 0.0, 0.0, 20.0)
            add(Label("阶段:"), 0, 0)
            add(comboStage, 1, 0)
            add(Label("排序权重:"), 2, 0)
            add(txtWeight, 3, 0)
            add(Label("重规划:"), 0, 1)
            add(comboReplan, 1, 1)
            add(Label("优先级:"), 2, 1)
            add(txtPriority, 3, 1)
        }

        val surplusBody = HBox(8.0).apply {
            padding = Insets(0.0, 0.0, 0.0, 20.0)
            children.addAll(
                chkThreshold,
                txtThreshold,
                Label("（不勾「设余费门槛」= 覆盖为「不设门槛」）").apply {
                    style = "-fx-text-fill: #7f8c8d; -fx-font-size: 11px;"
                }
            )
        }

        val scroll = ScrollPane(
            VBox(10.0).apply {
                padding = Insets(8.0)
                children.addAll(
                    section(
                        "🌲 树维度",
                        HBox(12.0, rbTreeInherit, rbTreeOverride, rbTreeDisable),
                        treeBody
                    ),
                    section(
                        "⏱️ 出牌时序维度",
                        HBox(12.0, rbTimingInherit, rbTimingOverride, rbTimingDisable),
                        timingBody
                    ),
                    section(
                        "💰 惜售门槛维度",
                        HBox(12.0, rbSurplusInherit, rbSurplusOverride, rbSurplusDisable),
                        surplusBody
                    )
                )
            }
        ).apply { isFitToWidth = true }
        VBox.setVgrow(scroll, Priority.ALWAYS)

        return VBox(8.0).apply {
            padding = Insets(10.0)
            children.addAll(scroll, errorLabel)
        }
    }

    private fun section(title: String, radioGroup: HBox, body: Node): VBox = VBox(6.0).apply {
        style = "-fx-border-color: #e2e8f0; -fx-border-radius: 4px; -fx-padding: 8px;"
        children.addAll(
            Label(title).apply {
                style = "-fx-font-weight: bold; -fx-font-size: 12px; -fx-text-fill: #2c3e50;"
            },
            radioGroup,
            body
        )
    }

    /** @return 错误描述；null = 通过 */
    private fun validate(): String? {
        if (rbTimingOverride.isSelected) {
            val weightText = txtWeight.text.trim()
            if (weightText.isNotEmpty() && weightText.toDoubleOrNull() == null) {
                return "排序权重不是有效数字: \"$weightText\"（留空 = 继承）"
            }
            val priorityText = txtPriority.text.trim()
            if (priorityText.isNotEmpty()) {
                val value = priorityText.toIntOrNull() ?: return "优先级不是有效整数: \"$priorityText\""
                if (value !in 1..9999) return "优先级超出范围: $value（可填 1~9999）"
            }
        }
        if (rbSurplusOverride.isSelected && chkThreshold.isSelected) {
            val value = txtThreshold.text.trim().toIntOrNull()
                ?: return "已勾选「设余费门槛」但数值无效（\"${txtThreshold.text}\"）；" +
                        "若要覆盖为无门槛请取消勾选该框"
            if (value < 1) return "余费门槛 N 必须为正整数: $value"
        }
        return null
    }

    private fun buildDraft(): DeckPurposeDraft {
        val newExcludedDims = mutableSetOf<String>()
        if (rbTreeDisable.isSelected) newExcludedDims.add(Dimension.PURPOSE_TREE)
        if (rbTimingDisable.isSelected) newExcludedDims.add(Dimension.PURPOSE_TIMING)
        if (rbSurplusDisable.isSelected) newExcludedDims.add(Dimension.PURPOSE_SURPLUS)

        return initial.copy(
            excludedTrees = if (rbTreeOverride.isSelected) treeChecks.filterValues { it.isSelected }.keys.toSet() else emptySet(),
            overrideTiming = rbTimingOverride.isSelected,
            timing = if (rbTimingOverride.isSelected) TimingOverride(
                defaultStage = (comboStage.value ?: "").takeIf { it in UseStage.entries.map { stage -> stage.name } },
                defaultOrderWeight = txtWeight.text.trim().toDoubleOrNull(),
                defaultReplanAfterUse = when (comboReplan.selectionModel.selectedIndex) {
                    1 -> true
                    2 -> false
                    else -> null
                },
                priority = txtPriority.text.trim().toIntOrNull()
            ) else initial.timing,
            overrideSurplus = rbSurplusOverride.isSelected,
            surplusThreshold = if (rbSurplusOverride.isSelected && chkThreshold.isSelected) {
                txtThreshold.text.trim().toIntOrNull()
            } else {
                null
            },
            excludedDimensions = newExcludedDims
        )
    }
}

