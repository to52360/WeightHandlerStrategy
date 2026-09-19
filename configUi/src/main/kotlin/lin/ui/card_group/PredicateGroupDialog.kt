package lin.ui.card_group

import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.*
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import lin.repository.condition_tree.ConditionTreeConfigRepository
import lin.ui.card_group.behavior.ConditionTreeOption
import lin.ui.condition_tree.ConditionTreeDialog
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

    /** 表单状态封装：configUi 规范要求 init 中状态变量/数据源超过 4 个须收敛为对象，不零散平铺。 */
    private class FormState {
        val nameField = TextField()
        val conditionTreeCombo = ComboBox<ConditionTreeOption>()
        val newTreeBtn = Button("新建")
        val editTreeBtn = Button("编辑")
        val includeDerivedCombo = ComboBox<String>()
    }

    private val form = FormState()

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
        val managerId = store.state.selectedManagerItem?.entity?.id
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

        form.newTreeBtn.setOnAction {
            val dialog = ConditionTreeDialog(autoCreateDraft = true, managerId = managerId)
            dialog.showAndWait().ifPresent { createdId ->
                refreshConditionTreeOptions()
                selectTreeById(createdId)
            }
        }
        form.editTreeBtn.setOnAction {
            val selectedId = form.conditionTreeCombo.value?.id
            if (selectedId.isNullOrEmpty()) {
                Alert(Alert.AlertType.WARNING, "请先在条件树下拉列表中选择一项。").apply {
                    headerText = "未选中条件树"
                }.showAndWait()
                return@setOnAction
            }
            val dialog = ConditionTreeDialog(initialSelectTreeId = selectedId, managerId = managerId)
            dialog.showAndWait().ifPresent { editedId ->
                refreshConditionTreeOptions()
                selectTreeById(editedId ?: selectedId)
            }
        }

        val treeRow = HBox(10.0).apply {
            alignment = Pos.CENTER_LEFT
            children.addAll(
                form.conditionTreeCombo,
                form.newTreeBtn,
                form.editTreeBtn
            )
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
                    children.addAll(Label("条件树:").apply { style = "-fx-font-weight: bold;" }, treeRow)
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
        val cid = form.conditionTreeCombo.value?.id
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
        store.addPredicateBinding(
            name = form.nameField.text.trim(),
            conditionId = form.conditionTreeCombo.value!!.id,
            includeDerived = includeDerived
        )
    }

    private fun refreshConditionTreeOptions() {
        val managerId = store.state.selectedManagerItem?.entity?.id
        val options = conditionTreeRepository.findMetaByManagerId(managerId).map { ConditionTreeOption(it.id, it.name) }
        form.conditionTreeCombo.items.setAll(options)
    }

    private fun selectTreeById(id: String?) {
        if (id.isNullOrEmpty()) {
            form.conditionTreeCombo.value = null
            return
        }
        val matched = form.conditionTreeCombo.items.find { it.id == id }
        if (matched != null) {
            form.conditionTreeCombo.value = matched
        } else {
            val tempOption = ConditionTreeOption(id, "自定义条件树 ($id)")
            form.conditionTreeCombo.items.add(tempOption)
            form.conditionTreeCombo.value = tempOption
        }
    }
}
