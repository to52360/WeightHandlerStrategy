package lin.ui.tree_config

import javafx.geometry.Insets
import javafx.scene.Node
import javafx.scene.control.*
import javafx.scene.layout.*
import lin.rule.build.DynamicFieldOption
import lin.rule.parse.FieldConstraint
import lin.rule.parse.FieldSpec
import lin.rule.parse.FieldType
import lin.ui.SelectOptionRegistry
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class DynamicFieldForm : KoinComponent {
    private val selectOptionRegistry: SelectOptionRegistry by inject()
    fun build(
        specs: List<FieldSpec>,
        existingValues: FieldValueReader,
        onFieldChanged: (propertyName: String, value: Any) -> Unit
    ): GridPane {
        val grid = GridPane().apply {
            hgap = 8.0
            vgap = 8.0
            padding = Insets(4.0, 0.0, 4.0, 0.0)
            columnConstraints.addAll(
                ColumnConstraints().apply {
                    hgrow = Priority.NEVER
                    prefWidth = 120.0
                    minWidth = 120.0
                },
                ColumnConstraints().apply { hgrow = Priority.ALWAYS }
            )
        }

        specs.forEachIndexed { row, spec ->
            val label = Label(buildLabel(spec)).apply {
                tooltip = Tooltip(spec.description)
                minWidth = Region.USE_PREF_SIZE
            }
            val control = buildControl(spec, existingValues.valueOf(spec.propertyName))
            attachChangeListener(control, spec) { value ->
                onFieldChanged(spec.propertyName, value)
            }

            grid.add(label, 0, row)
            grid.add(control, 1, row)
            GridPane.setHgrow(control, Priority.ALWAYS)
        }

        return grid
    }

    private fun buildLabel(spec: FieldSpec): String {
        val required = if (spec.constraints.contains(FieldConstraint.Required)) " *" else ""
        return "${spec.name}$required:"
    }

    private fun buildControl(spec: FieldSpec, currentValue: Any?): Node {
        return when (val type = spec.typeStruct) {
            is FieldType.DoubleType -> TextField(currentValue?.toString() ?: "").apply {
                maxWidth = Double.MAX_VALUE
            }

            is FieldType.IntType -> TextField(currentValue?.toString() ?: "").apply {
                maxWidth = Double.MAX_VALUE
            }

            is FieldType.StringType -> TextField(currentValue?.toString() ?: "").apply {
                maxWidth = Double.MAX_VALUE
            }

            is FieldType.BooleanType -> CheckBox().apply {
                isSelected = (currentValue as? Boolean) ?: false
            }

            is FieldType.SelectType -> {
                val combo = ComboBox<DynamicFieldOption>().apply {
                    maxWidth = Double.MAX_VALUE
                    setCellFactory {
                        object : ListCell<DynamicFieldOption>() {
                            override fun updateItem(item: DynamicFieldOption?, empty: Boolean) {
                                super.updateItem(item, empty)
                                text = if (empty || item == null) null else item.label
                            }
                        }
                    }
                    buttonCell = cellFactory.call(null)
                }
                val options = loadOptions(type.dataSourceId)
                combo.items.addAll(options)
                val selectedOpt = options.firstOrNull { it.value == currentValue?.toString() }
                if (selectedOpt != null) {
                    combo.selectionModel.select(selectedOpt)
                }
                combo
            }

            is FieldType.ListType -> buildListControl(type, currentValue)
        }
    }

    private fun buildListControl(type: FieldType.ListType, currentValue: Any?): Node {
        val elementType = type.elementType
        return if (elementType is FieldType.SelectType) {
            buildMultiSelectControl(elementType, currentValue)
        } else {
            val text =
                if (currentValue is List<*>) currentValue.joinToString("\n") else currentValue?.toString() ?: ""
            TextArea(text).apply {
                prefRowCount = 3
                promptText = "每行一个值"
                maxWidth = Double.MAX_VALUE
            }
        }
    }

    private fun buildMultiSelectControl(elementType: FieldType.SelectType, currentValue: Any?): MenuButton {
        val menu = MenuButton("请选择...").apply { maxWidth = Double.MAX_VALUE }
        val content = VBox(5.0).apply { padding = Insets(5.0, 10.0, 5.0, 10.0) }
        val options = loadOptions(elementType.dataSourceId)
        val selectedValues = (currentValue as? List<*>)?.map { it.toString() }?.toSet() ?: emptySet()

        options.forEach { opt ->
            val cb = CheckBox(opt.label).apply {
                userData = opt.value
                isSelected = selectedValues.contains(opt.value.toString())
            }
            content.children.add(cb)
        }

        menu.items.add(CustomMenuItem(content).apply { isHideOnClick = false })
        updateMultiSelectText(menu, content)
        return menu
    }

    private fun loadOptions(dataSourceId: String): List<DynamicFieldOption> {
        val builtIn = when (dataSourceId) {
            "score_effect_types" -> listOf(
                DynamicFieldOption(label = "固定分", value = "constant"),
                DynamicFieldOption(label = "数据源评分", value = "source")
            )

            else -> null
        }
        if (builtIn != null) return builtIn
        return selectOptionRegistry.getOptions(dataSourceId)
    }

    private fun updateMultiSelectText(menu: MenuButton, content: VBox) {
        val selectedLabels = content.children.filterIsInstance<CheckBox>()
            .filter { it.isSelected }
            .map { it.text }
        menu.text = if (selectedLabels.isEmpty()) "请选择..." else selectedLabels.joinToString(", ")
    }

    private fun attachChangeListener(
        control: Node,
        spec: FieldSpec,
        onChanged: (Any) -> Unit
    ) {
        when (control) {
            is TextField -> control.textProperty().addListener { _, _, newVal ->
                onChanged(parseTextValue(spec, newVal))
            }

            is CheckBox -> control.selectedProperty().addListener { _, _, newVal ->
                onChanged(newVal)
            }

            is ComboBox<*> -> control.selectionModel.selectedItemProperty().addListener { _, _, newVal ->
                if (newVal != null) {
                    val finalVal = if (newVal is DynamicFieldOption) newVal.value ?: "" else newVal
                    onChanged(finalVal)
                }
            }

            is TextArea -> control.textProperty().addListener { _, _, newVal ->
                onChanged(parseListTextValue(spec, newVal))
            }

            is MenuButton -> attachMultiSelectListener(control, spec, onChanged)
        }
    }

    private fun parseTextValue(spec: FieldSpec, value: String): Any {
        return when (spec.typeStruct) {
            FieldType.DoubleType -> value.toDoubleOrNull() ?: 0.0
            FieldType.IntType -> value.toIntOrNull() ?: 0
            else -> value
        }
    }

    private fun parseListTextValue(spec: FieldSpec, value: String): List<Any> {
        val lines = value.lines().filter { it.isNotBlank() }
        val elementType = (spec.typeStruct as? FieldType.ListType)?.elementType
        return when (elementType) {
            FieldType.IntType -> lines.mapNotNull { it.toIntOrNull() }
            FieldType.DoubleType -> lines.mapNotNull { it.toDoubleOrNull() }
            else -> lines
        }
    }

    private fun attachMultiSelectListener(
        menu: MenuButton,
        spec: FieldSpec,
        onChanged: (Any) -> Unit
    ) {
        val customItem = menu.items.firstOrNull() as? CustomMenuItem
        val vBox = customItem?.content as? VBox
        vBox?.children?.filterIsInstance<CheckBox>()?.forEach { cb ->
            cb.selectedProperty().addListener { _, _, _ ->
                val selectedValues = vBox.children.filterIsInstance<CheckBox>()
                    .filter { it.isSelected }
                    .map { it.userData }

                val listType = spec.typeStruct as? FieldType.ListType
                val elementType = listType?.elementType
                val finalValues =
                    if (elementType is FieldType.SelectType && elementType.valueType is FieldType.IntType) {
                        selectedValues.mapNotNull { it.toString().toIntOrNull() }
                    } else {
                        selectedValues
                    }

                updateMultiSelectText(menu, vBox)
                onChanged(finalValues)
            }
        }
    }
}

fun interface FieldValueReader {
    fun valueOf(propertyName: String): Any?
}
