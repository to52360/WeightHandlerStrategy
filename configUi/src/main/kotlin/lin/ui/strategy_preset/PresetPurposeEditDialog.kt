package lin.ui.strategy_preset

import javafx.event.ActionEvent
import javafx.geometry.Insets
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.CheckBox
import javafx.scene.control.ComboBox
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.control.TextField
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import lin.ui.card_group.behavior.BehaviorDisplayMappers
import lin.repository.card_group.TimingOverride
import lin.repository.tree_config.TreeConfigEntity

/**
 * 单个用途的三维度声明编辑弹窗（T-TG-037 / D-TG-019，布局候选 L1 的「编辑」落点）。
 *
 * 一个用途的三条信息（树 / 时序 / 惜售）在**同一个弹窗里一次配完**，不再切页签。
 * 每个维度各自一个「声明」勾：**勾 = 声明（给出具体值，落库）/ 不勾 = 未声明（不落库）**；
 * 字段级没有「不覆盖」（预设侧非覆盖语义）⇒ 布尔字段用普通勾选框即可，三态消失。
 *
 * 数字字段留空 = 落全局默认值（数字不存在「未声明 vs 声明为特殊值」的歧义，故不设三态）。
 */
class PresetPurposeEditDialog(
    private val initial: PurposeDeclarationDraft,
    candidateTrees: List<TreeConfigEntity>,
    displayName: String = initial.tagId
) : Dialog<PurposeDeclarationDraft>() {

    private val chkTrees = CheckBox("声明树白名单（不勾 = 该用途在树维度未声明 ⇒ 其全局用途树不输出）")
    private val treeChecks = mutableMapOf<String, CheckBox>()

    private val chkTiming = CheckBox("声明出牌时序（勾 = 该用途产生一条完整规则，参与阶段排序与 priority 选优）")
    private val comboStage = ComboBox<String>().apply {
        items.setAll(BehaviorDisplayMappers.allStageLabels())
        prefWidth = 160.0
    }
    private val txtWeight = TextField().apply {
        prefWidth = 90.0
        promptText = "默认: ${initial.rule.defaultOrderWeight}"
    }
    private val chkReplan = CheckBox("打出后重新评估")
    private val txtPriority = TextField().apply {
        prefWidth = 70.0
        promptText = "默认: ${initial.rule.priority}"
    }

    private val chkSurplus = CheckBox("声明惜售门槛（不勾 = 该用途在惜售维度未声明）")
    private val chkThreshold = CheckBox("设余费门槛 N")
    private val txtThreshold = TextField().apply {
        prefWidth = 70.0
        promptText = "正整数"
    }

    private val errorLabel = Label().apply {
        style = "-fx-font-size: 11px; -fx-text-fill: #c0392b; -fx-font-weight: bold;"
        isVisible = false
        isManaged = false
    }

    init {
        title = "用途声明 - [$displayName]"
        headerText = "用途 [$displayName] 的声明（三维度各自独立：勾 = 声明并落库，不勾 = 未声明）"

        val dialogPane = this.dialogPane
        dialogPane.prefWidth = 620.0
        dialogPane.prefHeight = 520.0
        dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)

        prefill(candidateTrees)
        wireEnablement()
        dialogPane.content = buildContent()

        val okBtn = dialogPane.lookupButton(ButtonType.OK) as? Button
        okBtn?.text = "应用声明"
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
        chkTrees.isSelected = initial.declaredTrees
        candidateTrees.forEach { tree ->
            val cb = CheckBox("${tree.name} [id: ${tree.id}]" + if (tree.enabled) "" else " (已禁用)").apply {
                isSelected = tree.id in initial.treeIds
                if (!tree.enabled) style = "-fx-text-fill: #95a5a6;"
            }
            treeChecks[tree.id] = cb
        }

        chkTiming.isSelected = initial.declaredTiming
        val rawStage = initial.timing.defaultStage ?: initial.rule.defaultStage
        comboStage.value = BehaviorDisplayMappers.stageToLabel(rawStage)
        txtWeight.text = initial.timing.defaultOrderWeight?.toString() ?: ""
        chkReplan.isSelected = initial.timing.defaultReplanAfterUse == true
        txtPriority.text = initial.timing.priority?.toString() ?: ""

        chkSurplus.isSelected = initial.declaredSurplus
        chkThreshold.isSelected = initial.surplusThreshold != null
        txtThreshold.text = initial.surplusThreshold?.toString() ?: ""
    }

    private fun wireEnablement() {
        treeChecks.values.forEach { it.disableProperty().bind(chkTrees.selectedProperty().not()) }
        listOf(comboStage, txtWeight, chkReplan, txtPriority).forEach {
            it.disableProperty().bind(chkTiming.selectedProperty().not())
        }
        chkThreshold.disableProperty().bind(chkSurplus.selectedProperty().not())
        txtThreshold.disableProperty().bind(chkSurplus.selectedProperty().and(chkThreshold.selectedProperty()).not())
    }

    private fun buildContent(): VBox {
        val treeSection = section(
            "树白名单",
            chkTrees,
            VBox(3.0).apply {
                padding = Insets(0.0, 0.0, 0.0, 20.0)
                if (treeChecks.isEmpty()) {
                    children.add(Label("该用途暂无全局用途树可保留").apply {
                        style = "-fx-text-fill: #95a5a6; -fx-font-size: 11px;"
                    })
                } else {
                    children.addAll(treeChecks.values)
                }
            }
        )

        val timingSection = section(
            "出牌时序",
            chkTiming,
            GridPane().apply {
                hgap = 8.0
                vgap = 6.0
                padding = Insets(0.0, 0.0, 0.0, 20.0)
                add(Label("阶段:"), 0, 0)
                add(comboStage, 1, 0)
                add(Label("排序权重:"), 2, 0)
                add(txtWeight, 3, 0)
                add(Label("优先级:"), 0, 1)
                add(txtPriority, 1, 1)
                add(chkReplan, 2, 1, 2, 1)
            }
        )

        val surplusSection = section(
            "惜售门槛",
            chkSurplus,
            HBox(8.0).apply {
                padding = Insets(0.0, 0.0, 0.0, 20.0)
                children.addAll(
                    chkThreshold,
                    txtThreshold,
                    Label("（不勾「设余费门槛」= 声明为「不设门槛」：付得起即垫）").apply {
                        style = "-fx-text-fill: #7f8c8d; -fx-font-size: 11px;"
                    }
                )
            }
        )

        val scroll = ScrollPane(VBox(10.0).apply {
            padding = Insets(8.0)
            children.addAll(treeSection, timingSection, surplusSection)
        }).apply {
            isFitToWidth = true
        }
        VBox.setVgrow(scroll, Priority.ALWAYS)

        return VBox(8.0).apply {
            padding = Insets(10.0)
            children.addAll(scroll, errorLabel)
        }
    }

    private fun section(title: String, master: CheckBox, body: javafx.scene.Node): VBox = VBox(6.0).apply {
        style = "-fx-border-color: #e2e8f0; -fx-border-radius: 4px; -fx-padding: 8px;"
        children.addAll(
            Label(title).apply {
                style = "-fx-font-weight: bold; -fx-font-size: 12px; -fx-text-fill: #2c3e50;"
            },
            master.apply { style = "-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #2980b9;" },
            body
        )
    }

    /** @return 错误描述；null = 通过 */
    private fun validate(): String? {
        if (chkTiming.isSelected) {
            val weightText = txtWeight.text.trim()
            if (weightText.isNotEmpty() && weightText.toDoubleOrNull() == null) {
                return "排序权重不是有效数字: \"$weightText\"（留空 = 用默认值）"
            }
            val priorityText = txtPriority.text.trim()
            if (priorityText.isNotEmpty()) {
                val value = priorityText.toIntOrNull() ?: return "优先级不是有效整数: \"$priorityText\""
                if (value !in 1..9999) return "优先级超出范围: $value（可填 1~9999）"
            }
        }
        if (chkSurplus.isSelected && chkThreshold.isSelected) {
            val value = txtThreshold.text.trim().toIntOrNull()
                ?: return "已勾选「设余费门槛」但数值无效（\"${txtThreshold.text}\"）；" +
                        "若要声明为无门槛请取消勾选该框"
            if (value < 1) return "余费门槛 N 必须为正整数: $value"
        }
        return null
    }

    private fun buildDraft(): PurposeDeclarationDraft = initial.copy(
        declaredTrees = chkTrees.isSelected,
        treeIds = treeChecks.filterValues { it.isSelected }.keys.toSet(),
        declaredTiming = chkTiming.isSelected,
        timing = TimingOverride(
            defaultStage = BehaviorDisplayMappers.labelToStageName(comboStage.value),
            defaultOrderWeight = txtWeight.text.trim().toDoubleOrNull(),
            defaultReplanAfterUse = chkReplan.isSelected,
            priority = txtPriority.text.trim().toIntOrNull()
        ),
        declaredSurplus = chkSurplus.isSelected,
        surplusThreshold = if (chkSurplus.isSelected && chkThreshold.isSelected) {
            txtThreshold.text.trim().toIntOrNull()
        } else {
            null
        }
    )
}
