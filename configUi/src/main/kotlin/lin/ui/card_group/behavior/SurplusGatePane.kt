package lin.ui.card_group.behavior

import javafx.beans.value.ObservableBooleanValue
import javafx.geometry.Pos
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.control.TextFormatter
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import lin.rule.tree.findSurplusGate
import lin.ui.card_group.WorkbenchStore

/**
 * SURPLUS_GATE 类型行为编辑面板：分组级余费门槛 N（D-012 垫后余量语义）。
 * 一类牌统一捏、不用逐卡设置（如解牌组统一 N=2 = 垫出后仍须剩 2 费）；空 = 未配置 = 付得起即垫（逐卡小数位仍优先）。
 * 自包含 UI 构造、编辑→State、State→UI 双向同步。
 */
class SurplusGatePane(
    private val store: WorkbenchStore,
    disableWhen: ObservableBooleanValue
) {
    val node: VBox

    private val gateField = TextField().apply {
        promptText = "1 ~ 9"
        prefWidth = 60.0
        disableProperty().bind(disableWhen)
        // 只允许 1~9 的数字输入
        textFormatter = TextFormatter<String> { change ->
            val newText = change.controlNewText
            change.text =
                if (newText.isEmpty() || newText.toIntOrNull()?.let { it in 1..9 } == true) change.text else ""
            change
        }
    }

    private var isUpdatingFromState = false

    init {
        node = VBox(8.0).apply {
            val row = HBox(10.0).apply {
                alignment = Pos.CENTER_LEFT
                children.addAll(
                    Label("垫后余量门槛 N:").apply { style = "-fx-font-weight: bold;" },
                    gateField,
                    Label("放行 ⟺ 空闲 ≥ 牌费 + N（垫出后仍须剩 N 费；如 2 费牌 N=2：空闲 4 才垫）。空 = 未配置 = 付得起即垫。只管余费垫牌，不改主搜索资格——主搜索资格由战术分（评估树 ts>0）决定")
                        .apply { style = "-fx-text-fill: #6c757d; -fx-font-size: 11px;" }
                )
            }
            children.add(row)
        }

        gateField.textProperty().addListener { _, _, newValue ->
            if (!isUpdatingFromState) {
                val threshold = newValue.toIntOrNull()?.coerceIn(1, 9)
                store.updateBindingSurplusGate(threshold)
            }
        }

        // ── State → UI ──
        store.stateProperty.addListener { _, _, _ -> syncFromState() }
        // 弹窗构造发生在最后一次 state 变化之后，listener 只覆盖未来变化；初始回显需主动同步一次
        syncFromState()
    }

    private fun syncFromState() {
        val idx = store.state.selectedBindingIndex
        val bindings = store.state.currentBindings
        val text = if (idx != null && idx in bindings.indices) {
            bindings[idx].behaviors.findSurplusGate()?.idleThreshold?.toString() ?: ""
        } else ""
        isUpdatingFromState = true
        try {
            if (gateField.text != text) gateField.text = text
        } finally {
            isUpdatingFromState = false
        }
    }
}
