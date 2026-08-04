package lin.ui.card_group.behavior

import javafx.beans.value.ObservableBooleanValue
import javafx.geometry.Pos
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.layout.HBox
import lin.bean.usePlan.ConditionalStageOverride
import lin.bean.usePlan.UseStage
import lin.rule.tree.findOverride
import lin.ui.card_group.WorkbenchStore

/**
 * OVERRIDE 类型行为编辑面板：阶段覆盖 + 重规划 + 排序权重 + 条件化阶段（动态排序）。
 * 自包含 UI 构造、编辑→State、State→UI 双向同步。
 */
class OverridePane(
    private val store: WorkbenchStore,
    disableWhen: ObservableBooleanValue
) {
    val node: HBox

    private val stageCombo = ComboBox<String>().apply {
        items.setAll(BehaviorDisplayMappers.allStageLabels())
        promptText = "出牌阶段覆盖"
        disableProperty().bind(disableWhen)
    }
    private val replanCombo = ComboBox<String>().apply {
        items.setAll("(不覆盖)", "是", "否")
        promptText = "出牌后重规划"
        disableProperty().bind(disableWhen)
    }
    private val weightField = TextField().apply {
        promptText = "排序权重"
        prefWidth = 80.0
        disableProperty().bind(disableWhen)
    }

    // 条件化阶段（动态排序）：conditionId 非空时启用；命中→conditionStageCombo，未命中→conditionElseCombo
    private val conditionIdField = TextField().apply {
        promptText = "条件树ID(留空=无)"
        prefWidth = 180.0
        disableProperty().bind(disableWhen)
    }
    private val conditionStageCombo = ComboBox<String>().apply {
        items.setAll(BehaviorDisplayMappers.allStageLabels())
        promptText = "命中阶段"
        disableProperty().bind(disableWhen)
    }
    private val conditionElseCombo = ComboBox<String>().apply {
        items.setAll(BehaviorDisplayMappers.allStageLabels())
        promptText = "未命中阶段"
        disableProperty().bind(disableWhen)
    }

    private var isUpdatingFromState = false

    init {
        node = HBox(15.0).apply {
            alignment = Pos.CENTER_LEFT
            children.addAll(
                Label("阶段覆盖:"), stageCombo,
                Label("重规划:"), replanCombo,
                Label("排序权重:"), weightField,
                Label("条件阶段:"), conditionIdField,
                Label("命中:"), conditionStageCombo,
                Label("未命中:"), conditionElseCombo
            )
        }

        // ── 编辑 → State ──
        stageCombo.valueProperty().addListener { _, _, newValue ->
            if (!isUpdatingFromState && newValue != null) {
                val stage = BehaviorDisplayMappers.labelToStageName(newValue)
                store.updateBindingStageOverride(stage)
            }
        }
        replanCombo.valueProperty().addListener { _, _, newValue ->
            if (!isUpdatingFromState && newValue != null) {
                val replan = when (newValue) { "是" -> true; "否" -> false; else -> null }
                store.updateBindingReplanAfterUse(replan)
            }
        }
        weightField.textProperty().addListener { _, _, newValue ->
            if (!isUpdatingFromState && newValue != null) {
                newValue.toDoubleOrNull()?.let { store.updateBindingOrderWeight(it) }
            }
        }
        conditionIdField.textProperty().addListener { _, _, _ -> emitConditionalStage() }
        conditionStageCombo.valueProperty().addListener { _, _, _ -> emitConditionalStage() }
        conditionElseCombo.valueProperty().addListener { _, _, _ -> emitConditionalStage() }

        // ── State → UI ──
        store.stateProperty.addListener { _, _, _ ->
            val idx = store.state.selectedBindingIndex
            val bindings = store.state.currentBindings
            if (idx != null && idx in bindings.indices) {
                val binding = bindings[idx]
                isUpdatingFromState = true
                try {
                    val stageVal = binding.behaviors.findOverride()?.stageOverride?.name
                    val stageLabel = BehaviorDisplayMappers.stageToLabel(stageVal)
                    if (stageCombo.value != stageLabel) {
                        stageCombo.value = stageLabel
                    }
                    val replanDisplay = when (binding.behaviors.findOverride()?.replanAfterUse) {
                        true -> "是"; false -> "否"; null -> "(不覆盖)"
                    }
                    if (replanCombo.value != replanDisplay) replanCombo.value = replanDisplay
                    val weightVal = binding.behaviors.findOverride()?.orderWeight
                    val weightStr = weightVal?.toString() ?: ""
                    if (weightField.text != weightStr) weightField.text = weightStr
                    val cs = binding.behaviors.findOverride()?.conditionalStage
                    val csCid = cs?.conditionId ?: ""
                    if (conditionIdField.text != csCid) conditionIdField.text = csCid
                    val csStageLabel = cs?.let { BehaviorDisplayMappers.stageToLabel(it.stage.name) }
                    if (conditionStageCombo.value != csStageLabel) conditionStageCombo.value = csStageLabel
                    val csElseLabel = cs?.elseStage?.let { BehaviorDisplayMappers.stageToLabel(it.name) }
                    if (conditionElseCombo.value != csElseLabel) conditionElseCombo.value = csElseLabel
                } finally { isUpdatingFromState = false }
            } else {
                isUpdatingFromState = true
                try {
                    stageCombo.value = null; replanCombo.value = null; weightField.clear()
                    conditionIdField.clear(); conditionStageCombo.value = null; conditionElseCombo.value = null
                } finally { isUpdatingFromState = false }
            }
        }
    }

    /** 三控件联动提交：conditionId 为空 → 清除；命中阶段未选 → 暂不提交（避免半配置状态）。 */
    private fun emitConditionalStage() {
        if (isUpdatingFromState) return
        val cid = conditionIdField.text?.trim()?.takeIf { it.isNotEmpty() }
        if (cid == null) {
            store.updateBindingConditionalStage(null)
            return
        }
        val stageName = BehaviorDisplayMappers.labelToStageName(conditionStageCombo.value)
            ?: return
        val elseName = BehaviorDisplayMappers.labelToStageName(conditionElseCombo.value)
        store.updateBindingConditionalStage(
            ConditionalStageOverride(
                conditionId = cid,
                stage = UseStage.valueOf(stageName),
                elseStage = elseName?.let { UseStage.valueOf(it) }
            )
        )
    }
}
