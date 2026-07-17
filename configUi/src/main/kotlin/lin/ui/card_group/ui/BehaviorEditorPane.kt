package lin.ui.card_group.ui

import javafx.beans.value.ObservableBooleanValue
import javafx.geometry.Pos
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox

/**
 * 行为编辑面板：编排各行为 type 的子面板。
 * 新增行为类型 = 新建 Pane 类 + 在此文件加一行 children.add 。
 */
class BehaviorEditorPane(
    private val store: WorkbenchStore,
    disableWhen: ObservableBooleanValue
) {
    val node: VBox

    private val nameField = TextField().apply {
        promptText = "分组名称"
        disableProperty().bind(disableWhen)
    }

    // ── 行为 type 子面板 ──
    private val overridePane = OverridePane(store, disableWhen)
    private val useActionPane = UseActionPane(store, disableWhen)

    private var isUpdatingFromState = false

    init {
        val nameRow = HBox(10.0).apply {
            alignment = Pos.CENTER_LEFT
            children.addAll(Label("分组名称:"), nameField)
        }

        node = VBox(5.0).apply {
            children.addAll(nameRow, overridePane.node, useActionPane.node)
        }

        // 通用：分组名称编辑 → Store
        nameField.textProperty().addListener { _, _, newValue ->
            if (!isUpdatingFromState && newValue != null) {
                store.updateBindingName(newValue)
            }
        }

        // 通用：State → 分组名称（各 type pane 内部各自监听 state 同步自身字段）
        store.stateProperty.addListener { _, _, _ ->
            val idx = store.state.selectedBindingIndex
            val bindings = store.state.currentBindings
            isUpdatingFromState = true
            try {
                if (idx != null && idx in bindings.indices) {
                    val binding = bindings[idx]
                    if (nameField.text != binding.name) nameField.text = binding.name
                } else {
                    nameField.clear()
                }
            } finally { isUpdatingFromState = false }
        }
    }
}
