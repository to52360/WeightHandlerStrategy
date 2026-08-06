package lin.ui.card_group.behavior

import javafx.beans.property.SimpleBooleanProperty
import javafx.geometry.Insets
import javafx.scene.control.ButtonType
import javafx.scene.control.Dialog
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import lin.ui.card_group.WorkbenchStore

/**
 * 分组行为策略配置弹窗：集中管理选定分组的阶段覆盖、动态条件阶段、使用动作等策略规则。
 */
class GroupBehaviorDialog(
    private val store: WorkbenchStore
) : Dialog<Unit>() {

    init {
        val selectedBinding = store.state.selectedBindingIndex?.let { idx ->
            store.state.currentBindings.getOrNull(idx)
        }
        val bindingName = selectedBinding?.name?.takeIf { it.isNotBlank() } ?: "未命名分组"
        title = "⚙️ 分组行为策略配置 - [$bindingName]"
        headerText = "配置选定分组的出牌阶段覆盖、动态条件阶段覆盖及使用动作编排。"

        val dialogPane = this.dialogPane
        dialogPane.buttonTypes.addAll(ButtonType.OK)
        dialogPane.prefWidth = 680.0
        dialogPane.prefHeight = 440.0

        val behaviorTabPane = BehaviorTabPane(store, SimpleBooleanProperty(false))

        val container = VBox(10.0).apply {
            padding = Insets(10.0)
            children.add(behaviorTabPane.node)
            VBox.setVgrow(behaviorTabPane.node, Priority.ALWAYS)
        }

        dialogPane.content = container
    }
}
