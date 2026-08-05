package lin.ui.condition_tree

import javafx.geometry.Insets
import javafx.scene.control.ButtonType
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.layout.VBox
import org.koin.core.component.KoinComponent

/**
 * 条件树可视化配置弹窗：用于在卡组行为配置等上下文中直接新建或编辑条件树。
 * 自包含 ConditionTreeWorkbench 独立视口，点击【完成】后自动刷新并选中目标条件树 ID。
 */
class ConditionTreeDialog(
    private val initialSelectTreeId: String? = null,
    private val autoCreateDraft: Boolean = false,
    private val managerId: String? = null
) : Dialog<String?>(), KoinComponent {

    private val workbench = ConditionTreeWorkbench().apply {
        if (managerId != null) {
            this.targetManagerId = managerId
        }
    }

    init {
        title =
            if (autoCreateDraft) "新建条件树" else if (initialSelectTreeId != null) "编辑条件树" else "条件树配置管理"
        headerText = "可视化配置条件节点规则，编辑保存后点击【完成】自动填入配置中。"

        val dialogPane = this.dialogPane
        dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)
        dialogPane.prefWidth = 980.0
        dialogPane.prefHeight = 680.0

        val tipLabel = Label("提示：编辑节点后请确保点击列表上方的【保存】按钮落地数据库，再点击【完成】确认选入。").apply {
            style = "-fx-text-fill: #0d6efd; -fx-font-size: 12px; -fx-font-weight: bold;"
        }

        val container = VBox(8.0).apply {
            padding = Insets(10.0)
            children.addAll(tipLabel, workbench)
        }
        dialogPane.content = container

        // 初始化选中或自动新建草稿
        if (autoCreateDraft) {
            workbench.addDraftItem("新建条件树")
        } else if (!initialSelectTreeId.isNullOrEmpty()) {
            val items = workbench.configListView.items
            val matched = items.find { it.id == initialSelectTreeId }
            if (matched != null) {
                workbench.configListView.selectionModel.select(matched)
            }
        }

        setResultConverter { buttonType ->
            if (buttonType == ButtonType.OK) {
                workbench.configListView.selectionModel.selectedItem?.id
            } else {
                null
            }
        }
    }
}
