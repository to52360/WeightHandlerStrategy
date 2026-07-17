package lin.ui.card_group.ui

import javafx.beans.value.ObservableBooleanValue
import javafx.geometry.Pos
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.layout.HBox
import lin.bean.usePlan.UseStage
import lin.rule.tree.CardGroupBinding
import lin.rule.tree.findOverride

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
        items.setAll(listOf("(不覆盖)") + UseStage.entries.map { it.name })
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
        node = HBox(10.0).apply {
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
                val stage = if (newValue == "(不覆盖)") null else newValue
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
                    if (stageCombo.value != (stageVal ?: "(不覆盖)")) {
                        stageCombo.value = stageVal ?: "(不覆盖)"
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
