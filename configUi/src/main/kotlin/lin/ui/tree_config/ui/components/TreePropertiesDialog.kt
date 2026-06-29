package lin.ui.tree_config.ui.components

import javafx.geometry.Insets
import javafx.scene.control.*
import javafx.scene.layout.GridPane
import javafx.scene.layout.VBox
import lin.dao.CardSelectOptionProvider
import lin.rule.tree.EvaluatorTreeBindingType
import lin.ui.card_group.ui.ActiveManagerHolder
import lin.ui.card_purpose.PurposeTagProvider
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class TreePropertiesDialog(
    initialName: String = "",
    initialDescription: String? = null,
    initialEnabled: Boolean = true,
    initialBindingType: EvaluatorTreeBindingType? = null,
    initialBindingIds: List<String> = emptyList(),
    initialManagerId: String? = null
) : Dialog<TreePropertiesDialog.Result>(), KoinComponent {

    data class Result(
        val name: String,
        val description: String?,
        val enabled: Boolean,
        val bindingType: EvaluatorTreeBindingType,
        val bindingIds: List<String>,
        val managerId: String?
    )

    init {
        title = "评估树属性"
        headerText = "设置评估树名称、状态与绑定目标"

        val dialogPane = this.dialogPane
        dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)

        val nameField = TextField(initialName).apply {
            promptText = "新配置名称"
        }
        val descField = TextField().apply {
            promptText = "描述（可选，AI 生成时可附带说明）"
            initialDescription?.let { text = it }
        }

        val enabledCheckBox = CheckBox("启用该评估树").apply {
            isSelected = initialEnabled
        }

        val managerIdSnapshot = initialManagerId
            ?: getKoin().get<ActiveManagerHolder>().activeManagerId

        val typeComboBox = ComboBox<String>().apply {
            items.addAll("绑定到卡组", "绑定到用途标签")
            selectionModel.selectFirst()
        }

        val groupCheckItems = mutableMapOf<String, CheckBox>()
        val groupVBox = VBox(5.0)
        try {
            val options = if (managerIdSnapshot != null)
                CardSelectOptionProvider().getOptionsByManager(managerIdSnapshot)
            else
                CardSelectOptionProvider().getOptions()
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
        val tagProvider: PurposeTagProvider by inject()
        tagProvider.tags().forEach { tagDef ->
            val cb = CheckBox(tagDef.displayName)
            tagCheckItems[tagDef.id.value] = cb
            tagVBox.children.add(cb)
        }
        val tagScrollPane = ScrollPane(tagVBox).apply {
            isFitToWidth = true
            prefHeight = 150.0
        }

        // 根据 initialBindingType 恢复选择
        when (initialBindingType) {
            EvaluatorTreeBindingType.PURPOSE_TAG -> {
                typeComboBox.selectionModel.select("绑定到用途标签")
                initialBindingIds.forEach { id -> tagCheckItems[id]?.isSelected = true }
            }

            EvaluatorTreeBindingType.GROUP -> {
                typeComboBox.selectionModel.select("绑定到卡组")
                initialBindingIds.forEach { id -> groupCheckItems[id]?.isSelected = true }
            }

            null -> typeComboBox.selectionModel.selectFirst()
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

        grid.add(Label("描述:"), 0, 1)
        grid.add(descField, 1, 1)

        grid.add(Label("状态:"), 0, 2)
        grid.add(enabledCheckBox, 1, 2)

        grid.add(Label("绑定目标:"), 0, 3)
        grid.add(bindingBox, 1, 3)

        dialogPane.content = grid

        setResultConverter { buttonType ->
            if (buttonType == ButtonType.OK) {
                val (bindingType, bindingIds) = if (typeComboBox.selectionModel.selectedItem == "绑定到卡组") {
                    EvaluatorTreeBindingType.GROUP to groupCheckItems.entries.filter { it.value.isSelected }
                        .map { it.key }
                } else {
                    EvaluatorTreeBindingType.PURPOSE_TAG to tagCheckItems.entries.filter { it.value.isSelected }
                        .map { it.key }
                }
                Result(
                    name = nameField.text,
                    description = descField.text.trim().takeIf { it.isNotBlank() },
                    enabled = enabledCheckBox.isSelected,
                    bindingType = bindingType,
                    bindingIds = bindingIds,
                    managerId = if (bindingType == EvaluatorTreeBindingType.GROUP) managerIdSnapshot else null
                )
            } else {
                null
            }
        }
    }
}
