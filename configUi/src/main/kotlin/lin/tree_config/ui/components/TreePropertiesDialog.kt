package lin.tree_config.ui.components

import javafx.geometry.Insets
import javafx.scene.control.*
import javafx.scene.layout.GridPane
import javafx.scene.layout.VBox
import lin.bean.usePlan.PurposeTagId
import lin.dao.CardSelectOptionProvider
import lin.rule.tree.EvaluatorTreeBinding
import lin.rule.tree.EvaluatorTreeBindingType

class TreePropertiesDialog(
    initialName: String = "",
    initialEnabled: Boolean = true,
    initialBindings: List<EvaluatorTreeBinding> = emptyList()
) : Dialog<TreePropertiesDialog.Result>() {

    data class Result(
        val name: String,
        val enabled: Boolean,
        val bindings: List<EvaluatorTreeBinding>
    )

    init {
        title = "评估树属性"
        headerText = "设置评估树名称、状态与绑定目标"

        val dialogPane = this.dialogPane
        dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)

        val nameField = TextField(initialName).apply {
            promptText = "新配置名称"
        }

        val enabledCheckBox = CheckBox("启用该评估树").apply {
            isSelected = initialEnabled
        }

        val typeComboBox = ComboBox<String>().apply {
            items.addAll("绑定到卡组", "绑定到用途标签")
            selectionModel.selectFirst()
        }

        val groupCheckItems = mutableMapOf<String, CheckBox>()
        val groupVBox = VBox(5.0)
        try {
            val options = CardSelectOptionProvider().getOptions()
            options.forEach { option ->
                val cb = CheckBox(option.label)
                groupCheckItems[option.value] = cb
                groupVBox.children.add(cb)
            }
        } catch (e: Exception) {
            System.err.println("加载分组数据失败: ${e.message}")
        }
        val groupScrollPane = ScrollPane(groupVBox).apply {
            isFitToWidth = true
            prefHeight = 150.0
        }

        val tagCheckItems = mutableMapOf<String, CheckBox>()
        val tagVBox = VBox(5.0)
        PurposeTagId.DEFAULTS.forEach { tag ->
            val cb = CheckBox(tag.value)
            tagCheckItems[tag.value] = cb
            tagVBox.children.add(cb)
        }
        val tagScrollPane = ScrollPane(tagVBox).apply {
            isFitToWidth = true
            prefHeight = 150.0
        }

        val initialGroupIds = initialBindings.filter { it.type == EvaluatorTreeBindingType.GROUP }.map { it.id }.toSet()
        val initialTagIds =
            initialBindings.filter { it.type == EvaluatorTreeBindingType.PURPOSE_TAG }.map { it.id }.toSet()

        groupCheckItems.forEach { (id, item) -> item.isSelected = initialGroupIds.contains(id) }
        tagCheckItems.forEach { (id, item) -> item.isSelected = initialTagIds.contains(id) }

        if (initialTagIds.isNotEmpty() && initialGroupIds.isEmpty()) {
            typeComboBox.selectionModel.select("绑定到用途标签")
        } else {
            typeComboBox.selectionModel.select("绑定到卡组")
        }

        val bindingBox = VBox(10.0).apply {
            val pane = if (typeComboBox.selectionModel.selectedItem == "绑定到卡组") groupScrollPane else tagScrollPane
            children.addAll(typeComboBox, pane)
        }

        typeComboBox.selectionModel.selectedItemProperty().addListener { _, _, newValue ->
            if (bindingBox.children.size > 1) {
                bindingBox.children.removeAt(1)
            }
            if (newValue == "绑定到卡组") {
                bindingBox.children.add(groupScrollPane)
            } else {
                bindingBox.children.add(tagScrollPane)
            }
        }

        val grid = GridPane().apply {
            hgap = 10.0
            vgap = 10.0
            padding = Insets(20.0, 50.0, 10.0, 10.0)
        }

        grid.add(Label("名称:"), 0, 0)
        grid.add(nameField, 1, 0)

        grid.add(Label("状态:"), 0, 1)
        grid.add(enabledCheckBox, 1, 1)

        grid.add(Label("绑定目标:"), 0, 2)
        grid.add(bindingBox, 1, 2)

        dialogPane.content = grid

        setResultConverter { buttonType ->
            if (buttonType == ButtonType.OK) {
                val bindings = mutableListOf<EvaluatorTreeBinding>()
                if (typeComboBox.selectionModel.selectedItem == "绑定到卡组") {
                    groupCheckItems.entries.filter { it.value.isSelected }.forEach {
                        bindings.add(EvaluatorTreeBinding(EvaluatorTreeBindingType.GROUP, it.key))
                    }
                } else {
                    tagCheckItems.entries.filter { it.value.isSelected }.forEach {
                        bindings.add(EvaluatorTreeBinding(EvaluatorTreeBindingType.PURPOSE_TAG, it.key))
                    }
                }
                Result(nameField.text, enabledCheckBox.isSelected, bindings)
            } else {
                null
            }
        }
    }
}
