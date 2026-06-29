package lin.ui.components

import javafx.scene.control.*
import javafx.scene.layout.VBox
import lin.ui.db.OrthogonalTemplateEntity
import lin.ui.db.TemplateGroupEntity
import lin.ui.db.TemplateGroupRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * 可复用的模板名称/描述/分组输入对话框，同时用于 正交条件 和 正交规则 的模板保存。
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
 *     val (name, desc, groupId) = result.get()
 *     ...
 * }
 * ```
 */
class TemplateNameDialog(
    title: String,
    headerText: String,
    namePromptText: String = "模板名称",
    descPromptText: String = "描述信息"
) : Dialog<Triple<String, String, String?>>(), KoinComponent {

    private val templateGroupRepo: TemplateGroupRepository by inject()

    init {
        this.title = title
        this.headerText = headerText

        dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)

        val nameInput = TextField().apply { promptText = namePromptText }
        val descInput = TextField().apply { promptText = descPromptText }

        fun groupCellText(item: TemplateGroupEntity?) = item?.name ?: ""
        val groupCombo = ComboBox<TemplateGroupEntity>().apply {
            promptText = "选择分组（可选）"
            maxWidth = Double.MAX_VALUE
            setCellFactory {
                object : ListCell<TemplateGroupEntity>() {
                    override fun updateItem(item: TemplateGroupEntity?, empty: Boolean) {
                        super.updateItem(item, empty); text = groupCellText(item)
                    }
                }
            }
            buttonCell = object : ListCell<TemplateGroupEntity>() {
                override fun updateItem(item: TemplateGroupEntity?, empty: Boolean) {
                    super.updateItem(item, empty); text = groupCellText(item)
                }
            }
        }
        groupCombo.items.addAll(templateGroupRepo.findAll())

        dialogPane.content = VBox(8.0).apply {
            children.addAll(
                Label("模板名称:"), nameInput,
                Label("描述:"), descInput,
                Label("分组:"), groupCombo
            )
        }

        setResultConverter { buttonType ->
            if (buttonType == ButtonType.OK) {
                Triple(nameInput.text.trim(), descInput.text.trim(), groupCombo.value?.id)
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
