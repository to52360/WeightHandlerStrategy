package lin.ui.card_group

import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.*
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import lin.repository.condition_tree.ConditionTreeConfigRepository
import lin.ui.components.action.ResourcePickerBar
import lin.ui.condition_tree.components.ConditionTreeCapabilities
import lin.ui.condition_tree.components.ConditionTreeOption
import lin.ui.condition_tree.components.selectedTreeId
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * T-007：快速建组入口——「按条件建组」谓词组弹窗。
 * 选择（或新建/编辑）条件树即可创建谓词组：成员由条件树对每张候选卡运行时判定，
 * 无需枚举卡牌（如「所有法术」= is_card_type(SPELL)）。
 */
class PredicateGroupDialog(
    private val store: WorkbenchStore
) : Dialog<Unit>(), KoinComponent {

    private val conditionTreeRepository: ConditionTreeConfigRepository by inject()

    /** 表单状态封装：收敛为纯业务字段与声明式选择器，符合 UI 状态规范。 */
    private class FormState(
        managerIdProvider: () -> String?,
        onRefreshRequested: () -> Unit
    ) {
        val nameField = TextField()
        val treePicker = ResourcePickerBar(
            promptText = "选择条件树...",
            capabilities = ConditionTreeCapabilities.defaultSet(
                managerIdProvider = managerIdProvider,
                onRefreshRequested = onRefreshRequested
            )
        ).apply {
            comboBox.prefWidth = 240.0
        }
        val includeDerivedCombo = ComboBox<String>()
    }

    private val form = FormState(
        managerIdProvider = { store.state.selectedManagerItem?.entity?.id },
        onRefreshRequested = { refreshConditionTreeOptions() }
    )

    init {
        title = "按条件建组 (谓词组)"
        headerText = "用条件树定义组成员（如「所有法术」），运行时对每张候选卡判定命中，无需枚举卡牌。"

        val dialogPane = this.dialogPane
        dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)
        dialogPane.content = buildContent()
        dialogPane.lookupButton(ButtonType.OK).addEventFilter(javafx.event.ActionEvent.ACTION) { event ->
            if (!validate()) event.consume()
        }
        setResultConverter { buttonType ->
            if (buttonType == ButtonType.OK) {
                submit()
            } else {
                null
            }
        }
    }

    private fun buildContent(): VBox {
        val nextNum = store.state.currentBindings.size + 1

        form.nameField.apply {
            text = "条件组 $nextNum"
            promptText = "分组名称"
        }
        form.includeDerivedCombo.apply {
            items.setAll("(未声明)", "是", "否")
            value = "(未声明)"
        }
        refreshConditionTreeOptions()

        form.treePicker.comboBox.setOnShowing {
            refreshConditionTreeOptions()
        }

        val root = VBox(12.0).apply {
            padding = Insets(15.0)
            children.addAll(
                HBox(10.0).apply {
                    alignment = Pos.CENTER_LEFT
                    children.addAll(Label("分组名称:").apply { style = "-fx-font-weight: bold;" }, form.nameField)
                },
                HBox(10.0).apply {
                    alignment = Pos.CENTER_LEFT
                    children.addAll(Label("条件树:").apply { style = "-fx-font-weight: bold;" }, form.treePicker)
                },
                HBox(10.0).apply {
                    alignment = Pos.CENTER_LEFT
                    children.addAll(
                        Label("纳入衍生卡:").apply { style = "-fx-font-weight: bold;" },
                        form.includeDerivedCombo
                    )
                },
                Label("提示：谓词组由条件树定义成员，无需手动选卡。未声明纳入衍生卡时回落卡组级默认（再回落 false）。").apply {
                    style = "-fx-text-fill: #6c757d; -fx-font-size: 12px;"
                    isWrapText = true
                }
            )
        }
        return root
    }

    private fun validate(): Boolean {
        if (form.nameField.text.isNullOrBlank()) {
            Alert(Alert.AlertType.WARNING, "请输入分组名称。").apply { headerText = "校验未通过" }.showAndWait()
            return false
        }
        val cid = form.treePicker.selectedTreeId
        if (cid.isNullOrEmpty()) {
            Alert(Alert.AlertType.WARNING, "请选择或新建一个条件树。").apply { headerText = "校验未通过" }.showAndWait()
            return false
        }
        return true
    }

    private fun submit() {
        val includeDerived = when (form.includeDerivedCombo.value) {
            "是" -> true
            "否" -> false
            else -> null
        }
        val cid = form.treePicker.selectedTreeId ?: return
        store.addPredicateBinding(
            name = form.nameField.text.trim(),
            conditionId = cid,
            includeDerived = includeDerived
        )
    }

    private fun refreshConditionTreeOptions() {
        val managerId = store.state.selectedManagerItem?.entity?.id
        val options = conditionTreeRepository.findMetaByManagerId(managerId).map { ConditionTreeOption(it.id, it.name) }
        form.treePicker.setItems(options, retainSelection = true)
    }
}
