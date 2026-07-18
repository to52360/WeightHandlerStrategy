package lin.ui.tree_config.ui.components

import javafx.geometry.Insets
import javafx.scene.control.*
import javafx.scene.layout.GridPane
import javafx.scene.layout.VBox
import lin.dao.CardSelectOptionProvider
import lin.rule.tree.EvaluatorTreeBindingType
import lin.ui.card_group.db.CardGroupService
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

    private val groupCheckItems = mutableMapOf<String, CheckBox>()
    private val tagCheckItems = mutableMapOf<String, CheckBox>()
    private val cardCheckItems = mutableMapOf<String, CheckBox>()

    init {
        title = "评估树属性"
        headerText = "设置评估树名称、状态与绑定目标"

        val dialogPane = this.dialogPane
        dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)

        val nameField = TextField(initialName).apply { promptText = "新配置名称" }
        val descField = TextField().apply {
            promptText = "描述（可选，AI 生成时可附带说明）"
            initialDescription?.let { text = it }
        }
        val enabledCheckBox = CheckBox("启用该评估树").apply { isSelected = initialEnabled }

        val managerIdSnapshot = initialManagerId
            ?: getKoin().get<ActiveManagerHolder>().activeManagerId

        val typeComboBox = ComboBox<String>().apply {
            items.addAll("绑定到卡组", "绑定到用途标签", "绑定到单卡")
            selectionModel.selectFirst()
        }

        // 创建三个绑定面板
        val groupScrollPane = createGroupPane(managerIdSnapshot)
        val tagScrollPane = createTagPane()
        val cardScrollPane = createCardPane()

        // 恢复选择并绑定视图
        restoreInitialSelection(initialBindingType, initialBindingIds, typeComboBox)

        val bindingBox = VBox(10.0).apply {
            val pane = when (typeComboBox.selectionModel.selectedItem) {
                "绑定到卡组" -> groupScrollPane
                "绑定到单卡" -> cardScrollPane
                else -> tagScrollPane
            }
            children.addAll(typeComboBox, pane)
        }

        typeComboBox.selectionModel.selectedItemProperty().addListener { _, _, newValue ->
            if (bindingBox.children.size > 1) {
                bindingBox.children.removeAt(1)
            }
            val pane = when (newValue) {
                "绑定到卡组" -> groupScrollPane
                "绑定到单卡" -> cardScrollPane
                else -> tagScrollPane
            }
            bindingBox.children.add(pane)
        }

        val managerLabel = Label(managerIdSnapshot ?: "全局通用 (未限定卡组)").apply {
            style = if (managerIdSnapshot != null) "-fx-font-weight: bold; -fx-text-fill: #2196F3;" else "-fx-font-weight: bold; -fx-text-fill: #4CAF50;"
        }

        val grid = GridPane().apply {
            hgap = 10.0
            vgap = 10.0
            padding = Insets(20.0, 50.0, 10.0, 10.0)
            add(Label("当前卡组环境:"), 0, 0)
            add(managerLabel, 1, 0)
            add(Label("名称:"), 0, 1)
            add(nameField, 1, 1)
            add(Label("描述:"), 0, 2)
            add(descField, 1, 2)
            add(Label("状态:"), 0, 3)
            add(enabledCheckBox, 1, 3)
            add(Label("绑定目标:"), 0, 4)
            add(bindingBox, 1, 4)
        }

        dialogPane.content = grid

        val okButton = dialogPane.lookupButton(ButtonType.OK) as Button
        bindOkButtonValidation(okButton, nameField, typeComboBox)
        setupResultConverter(nameField, descField, enabledCheckBox, typeComboBox, managerIdSnapshot)
    }

    private fun createGroupPane(managerId: String?): ScrollPane {
        val groupVBox = VBox(5.0)
        try {
            val options = if (managerId != null)
                CardSelectOptionProvider().getOptionsByManager(managerId)
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
        return ScrollPane(groupVBox).apply {
            isFitToWidth = true
            prefHeight = 150.0
        }
    }

    private fun createTagPane(): ScrollPane {
        val tagVBox = VBox(5.0)
        val tagProvider: PurposeTagProvider by inject()
        tagProvider.tags().forEach { tagDef ->
            val cb = CheckBox(tagDef.displayName)
            tagCheckItems[tagDef.id.value] = cb
            tagVBox.children.add(cb)
        }
        return ScrollPane(tagVBox).apply {
            isFitToWidth = true
            prefHeight = 150.0
        }
    }

    private fun createCardPane(): ScrollPane {
        val cardVBox = VBox(5.0)
        try {
            val service = getKoin().get<CardGroupService>()
            val allCardIds = service.loadAll(onlyEnabled = true)
                .flatMap { it.bindings }
                .flatMap { it.cardIds }
                .distinct()
            allCardIds.forEach { cardId ->
                val cb = CheckBox(cardId)
                cardCheckItems[cardId] = cb
                cardVBox.children.add(cb)
            }
        } catch (e: Exception) {
            System.err.println("加载卡牌数据失败: ${e.message}")
        }
        return ScrollPane(cardVBox).apply {
            isFitToWidth = true
            prefHeight = 150.0
        }
    }

    private fun restoreInitialSelection(
        initialType: EvaluatorTreeBindingType?,
        initialIds: List<String>,
        typeComboBox: ComboBox<String>
    ) {
        when (initialType) {
            EvaluatorTreeBindingType.PURPOSE_TAG -> {
                typeComboBox.selectionModel.select("绑定到用途标签")
                initialIds.forEach { id -> tagCheckItems[id]?.isSelected = true }
            }

            EvaluatorTreeBindingType.GROUP -> {
                typeComboBox.selectionModel.select("绑定到卡组")
                initialIds.forEach { id -> groupCheckItems[id]?.isSelected = true }
            }

            EvaluatorTreeBindingType.CARD -> {
                typeComboBox.selectionModel.select("绑定到单卡")
                initialIds.forEach { id -> cardCheckItems[id]?.isSelected = true }
            }

            null -> typeComboBox.selectionModel.selectFirst()
        }
    }

    private fun bindOkButtonValidation(okButton: Button, nameField: TextField, typeComboBox: ComboBox<String>) {
        okButton.addEventFilter(javafx.event.ActionEvent.ACTION) { event ->
            if (nameField.text.trim().isBlank()) {
                Alert(Alert.AlertType.WARNING).apply {
                    title = "校验未通过"
                    headerText = "请输入评估树名称"
                }.showAndWait()
                event.consume()
                return@addEventFilter
            }

            val selected = typeComboBox.selectionModel.selectedItem
            val selectedBindingIds = when (selected) {
                "绑定到卡组" -> groupCheckItems.entries.filter { it.value.isSelected }.map { it.key }
                "绑定到单卡" -> cardCheckItems.entries.filter { it.value.isSelected }.map { it.key }
                else -> tagCheckItems.entries.filter { it.value.isSelected }.map { it.key }
            }

            if (selectedBindingIds.isEmpty()) {
                Alert(Alert.AlertType.WARNING).apply {
                    title = "校验未通过"
                    headerText = "必须选择至少一个绑定目标！"
                }.showAndWait()
                event.consume()
                return@addEventFilter
            }
        }
    }

    private fun setupResultConverter(
        nameField: TextField,
        descField: TextField,
        enabledCheckBox: CheckBox,
        typeComboBox: ComboBox<String>,
        managerIdSnapshot: String?
    ) {
        setResultConverter { buttonType ->
            if (buttonType == ButtonType.OK) {
                val selected = typeComboBox.selectionModel.selectedItem
                val (bindingType, bindingIds) = when (selected) {
                    "绑定到卡组" -> EvaluatorTreeBindingType.GROUP to groupCheckItems.entries.filter { it.value.isSelected }
                        .map { it.key }
                    "绑定到单卡" -> EvaluatorTreeBindingType.CARD to cardCheckItems.entries.filter { it.value.isSelected }
                        .map { it.key }
                    else -> EvaluatorTreeBindingType.PURPOSE_TAG to tagCheckItems.entries.filter { it.value.isSelected }
                        .map { it.key }
                }
                Result(
                    name = nameField.text.trim(),
                    description = descField.text.trim().takeIf { it.isNotBlank() },
                    enabled = enabledCheckBox.isSelected,
                    bindingType = bindingType,
                    bindingIds = bindingIds,
                    managerId = managerIdSnapshot
                )
            } else {
                null
            }
        }
    }
}
