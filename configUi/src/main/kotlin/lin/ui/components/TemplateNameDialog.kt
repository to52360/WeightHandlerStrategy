package lin.ui.components

import javafx.scene.control.*
import javafx.scene.layout.VBox
import lin.orthogonal_template.db.OrthogonalTemplateEntity

/**
 * 可复用的模板名称/描述输入对话框，同时用于 正交条件 和 正交规则 的模板保存。
 *
 * 用法：
 * ```
 * val dialog = TemplateNameDialog(
 *     title = "保存为正交条件模板",
 *     headerText = "请输入模板的名称和描述",
 *     namePromptText = "模板名称 (例如: 己方手牌数量>=3)",
 *     descPromptText = "描述信息 (例如: 适用于快速铺场卡组)"
 * )
 * val result = dialog.showAndWait()
 * if (result.isPresent) {
 *     val (name, desc) = result.get()
 *     ...
 * }
 * ```
 */
class TemplateNameDialog(
    title: String,
    headerText: String,
    namePromptText: String = "模板名称",
    descPromptText: String = "描述信息"
) : Dialog<Pair<String, String>>() {

    init {
        this.title = title
        this.headerText = headerText

        dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)

        val nameInput = TextField().apply { promptText = namePromptText }
        val descInput = TextField().apply { promptText = descPromptText }

        dialogPane.content = VBox(8.0).apply {
            children.addAll(
                Label("模板名称:"), nameInput,
                Label("描述:"), descInput
            )
        }

        setResultConverter { buttonType ->
            if (buttonType == ButtonType.OK) {
                nameInput.text.trim() to descInput.text.trim()
            } else null
        }
    }

    companion object {
        /**
         * 为模板下拉框应用统一的 cell factory（格式：name (description 或 "无描述")）。
         */
        fun applyTemplateEntityCellFactory(combo: ComboBox<OrthogonalTemplateEntity>) {
            combo.setCellFactory {
                object : ListCell<OrthogonalTemplateEntity>() {
                    override fun updateItem(item: OrthogonalTemplateEntity?, empty: Boolean) {
                        super.updateItem(item, empty)
                        text = if (empty || item == null) null else "${item.name} (${item.description ?: "无描述"})"
                    }
                }
            }
            combo.buttonCell = combo.cellFactory.call(null)
        }
    }
}
