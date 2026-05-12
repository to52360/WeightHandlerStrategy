package lin.tree_config.ui.components

import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.*
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import lin.dao.CardSelectOptionProvider

class BindGroupSelector : HBox(10.0) {
    val bindGroupMenuButton = MenuButton("请选择绑定卡组分组...")
    private val groupCheckItems = mutableMapOf<String, CheckBox>()

    init {
        alignment = Pos.CENTER_LEFT
        val label = Label("绑定卡组分组:").apply {
            val asterisk = Label("*").apply { style = "-fx-text-fill: red;" }
            graphic = asterisk
            contentDisplay = ContentDisplay.RIGHT
        }

        try {
            val options = CardSelectOptionProvider().getOptions()
            val content = VBox(5.0).apply { padding = Insets(5.0, 10.0, 5.0, 10.0) }
            options.forEach { option ->
                val cb = CheckBox(option.label).apply {
                    userData = option.value
                    selectedProperty().addListener { _, _, _ -> updateMenuButtonText() }
                }
                groupCheckItems[option.value] = cb
                content.children.add(cb)
            }
            val customMenuItem = CustomMenuItem(content).apply { isHideOnClick = false }
            bindGroupMenuButton.items.add(customMenuItem)
        } catch (e: Exception) {
            System.err.println("加载分组数据失败: ${e.message}")
        }

        children.addAll(label, bindGroupMenuButton)
    }

    private fun updateMenuButtonText() {
        val selectedLabels = groupCheckItems.values
            .filter { it.isSelected }
            .map { it.text }
        bindGroupMenuButton.text = if (selectedLabels.isEmpty()) "请选择..." else selectedLabels.joinToString(", ")
    }

    fun getSelectedGroupIds(): List<String> {
        return groupCheckItems.entries
            .filter { it.value.isSelected }
            .map { it.key }
    }

    fun setSelectedGroupIds(ids: List<String>) {
        groupCheckItems.forEach { (id, item) ->
            item.isSelected = ids.contains(id)
        }
        updateMenuButtonText()
    }
}
