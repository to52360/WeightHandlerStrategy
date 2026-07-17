package lin.ui.card_group.ui

import javafx.beans.value.ObservableBooleanValue
import javafx.geometry.Pos
import javafx.scene.control.CheckBox
import javafx.scene.control.Label
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import lin.domain.use.UseActionRegistry
import lin.rule.tree.CardGroupBinding
import lin.rule.tree.findExtraConfig
import lin.rule.tree.findUseActions

/**
 * USE_ACTION 类型行为编辑面板：使用动作勾选 + stat_dimensions 多选框。
 * 自包含 UI 构造、编辑→State、State→UI 双向同步。
 */
class UseActionPane(
    private val store: WorkbenchStore,
    disableWhen: ObservableBooleanValue
) {
    val node: VBox

    private val actionCheckMap = linkedMapOf<String, CheckBox>()
    private val statDimsPane = StatDimensionsPane(store, disableWhen)

    private var isUpdatingFromState = false

    init {
        val useActionBox = VBox(3.0)
        UseActionRegistry.knownActionIds().forEach { actionId ->
            val cb = CheckBox(actionId).apply { disableProperty().bind(disableWhen) }
            cb.selectedProperty().addListener { _, _, newValue ->
                if (!isUpdatingFromState) {
                    store.updateBindingUseAction(actionId, newValue)
                }
                // ARCH-UNSETTLED Q-3: "RECORD_PLAY" 字符串硬编码匹配子面板，
                // 后续 action 增多会 if-else 膨胀。应走声明式——每个 actionId 关联可选子面板配置。
                if (actionId == "RECORD_PLAY") {
                    statDimsPane.isVisible = newValue
                }
            }
            actionCheckMap[actionId] = cb
            useActionBox.children.add(cb)
        }

        val useActionRow = HBox(10.0).apply {
            alignment = Pos.CENTER_LEFT
            children.addAll(Label("使用动作:"), useActionBox)
        }

        node = VBox(5.0).apply {
            children.addAll(useActionRow, statDimsPane.node)
        }

        // ── State → UI ──
        store.stateProperty.addListener { _, _, _ ->
            val idx = store.state.selectedBindingIndex
            val bindings = store.state.currentBindings
            if (idx != null && idx in bindings.indices) {
                val binding = bindings[idx]
                isUpdatingFromState = true
                try {
                    actionCheckMap.forEach { (actionId, cb) ->
                        val selected = binding.behaviors.findUseActions().contains(actionId)
                        if (cb.isSelected != selected) cb.isSelected = selected
                    }
                    statDimsPane.syncFromConfig(binding.behaviors.findExtraConfig())
                    statDimsPane.isVisible = binding.behaviors.findUseActions().contains("RECORD_PLAY")
                } finally { isUpdatingFromState = false }
            } else {
                isUpdatingFromState = true
                try {
                    actionCheckMap.forEach { (_, cb) -> cb.isSelected = false }
                    statDimsPane.clearSelection()
                } finally { isUpdatingFromState = false }
            }
        }
    }
}
