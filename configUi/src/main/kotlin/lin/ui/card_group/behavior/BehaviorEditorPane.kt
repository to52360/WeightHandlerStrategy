package lin.ui.card_group.behavior

import javafx.beans.value.ObservableBooleanValue
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Label
import javafx.scene.control.Separator
import javafx.scene.control.TextField
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import lin.ui.card_group.WorkbenchStore

/**
 * 行为编辑面板：编排各行为 type 的子面板。
 * 采用了具有清晰边界与分类隔离的卡片面板布局。
 */
class BehaviorEditorPane(
    private val store: WorkbenchStore,
    disableWhen: ObservableBooleanValue
) {
    val node: VBox

    private val nameField = TextField().apply {
        promptText = "输入分组名称"
        prefWidth = 200.0
        disableProperty().bind(disableWhen)
    }

    // ── 行为 type 子面板 ──
    private val overridePane = OverridePane(store, disableWhen)
    private val useActionPane = UseActionPane(store, disableWhen)

    private var isUpdatingFromState = false

    init {
        val titleLabel = Label("选定分组行为参数配置").apply {
            style = "-fx-font-weight: bold; -fx-font-size: 13px; -fx-text-fill: #212529;"
        }

        val nameRow = HBox(10.0).apply {
            alignment = Pos.CENTER_LEFT
            children.addAll(
                Label("分组名称:").apply { style = "-fx-font-weight: bold;" },
                nameField
            )
        }

        node = VBox(10.0).apply {
            padding = Insets(12.0)
            style =
                "-fx-background-color: #ffffff; -fx-border-color: #ced4da; -fx-border-radius: 6; -fx-background-radius: 6;"
            children.addAll(
                titleLabel,
                nameRow,
                Separator(),
                overridePane.node,
                Separator(),
                useActionPane.node
            )
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
