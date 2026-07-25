package lin.ui.card_group.behavior

import javafx.beans.value.ObservableBooleanValue
import javafx.geometry.Pos
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.layout.HBox
import lin.rule.tree.findOverride
import lin.ui.card_group.WorkbenchStore

/**
 * OVERRIDE 类型行为编辑面板：阶段覆盖 + 重规划 + 排序权重。
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

    private var isUpdatingFromState = false

    init {
        node = HBox(15.0).apply {
            alignment = Pos.CENTER_LEFT
            children.addAll(
                Label("阶段覆盖:"), stageCombo,
                Label("重规划:"), replanCombo,
                Label("排序权重:"), weightField
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
                } finally { isUpdatingFromState = false }
            } else {
                isUpdatingFromState = true
                try {
                    stageCombo.value = null; replanCombo.value = null; weightField.clear()
                } finally { isUpdatingFromState = false }
            }
        }
    }
}
