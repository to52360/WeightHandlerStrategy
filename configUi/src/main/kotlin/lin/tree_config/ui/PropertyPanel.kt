package lin.tree_config.ui

import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.*
import javafx.scene.layout.*
import lin.rule.build.DynamicFieldOption
import lin.rule.parse.FieldConstraint
import lin.rule.parse.FieldType
import lin.rule.parse.RuleFieldSpec
import lin.rule.registry.RuleRegistry
import lin.rule.registry.RuleUiItem
import lin.rule.tree.EvaluatorPayload
import lin.rule.tree.RuleConfig
import lin.serviceLoader.provider.SelectOptionProvider
import lin.utils.serviceLoader.ServiceLoaderUtils
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * 右侧属性面板：根据选中节点类型动态渲染不同的属性表单
 */
class PropertyPanel : VBox(8.0), KoinComponent {

    private val ruleRegistry: RuleRegistry by inject()

    // 把当前面板状态回传出去 (用于保存时抓取修改后的 RuleConfig)
    var onRuleConfigChanged: ((String, RuleConfig) -> Unit)? = null

    private val optionProviders: Map<String, SelectOptionProvider> by lazy {
        ServiceLoaderUtils.getCacheServices(SelectOptionProvider::class.java)
            .associateBy { it.dataSourceId }
    }

    init {
        padding = Insets(10.0)
        children.add(Label("节点属性").apply {
            style = "-fx-font-weight: bold; -fx-padding: 0 0 5 0;"
        })
        showPlaceholder()
    }

    fun showPlaceholder() {
        clearContent()
        children.add(Label("请在中间树中选择一个节点...").apply {
            style = "-fx-text-fill: #888;"
        })
    }

    fun showStructureNode(nodeType: LogicNodeType) {
        clearContent()
        children.add(Label("类型: ${nodeType.name}").apply {
            style = "-fx-font-size: 14px;"
        })
        children.add(Label("结构节点，无需配置额外属性。").apply {
            style = "-fx-text-fill: #888;"
        })
    }

    fun showRuleConfigNode(wrapper: LogicNodeWrapper<EvaluatorPayload>, ruleConfigs: MutableMap<String, RuleConfig>) {
        clearContent()

        val payload = wrapper.payload
        val nodeId = when (payload) {
            is EvaluatorPayload.Rule -> payload.nodeId
            is EvaluatorPayload.BranchCondition -> payload.nodeId
            else -> return
        }
        val existing = ruleConfigs[nodeId]
        val isBranch = wrapper.type == LogicNodeType.BRANCH

        // ---- 标题 ----
        val typeLabel = if (isBranch) "Branch 节点" else "Rule 节点"
        children.add(Label("$typeLabel (nodeId: $nodeId)").apply {
            style = "-fx-font-weight: bold;"
        })
        if (isBranch) {
            // TODO: BRANCH 节点的 weight/mismatchedWeight 语义与 RULE 节点有所不同：
            // BRANCH 的 weight 在条件成立时可作为额外加成分数，但其核心作用是路由分支而非直接贡献分数。
            // 后续需与运行时 EvaluatorTreeInstance 的执行逻辑对齐，明确 BranchNode 的权重是否需要独立处理。
            children.add(Label("条件满足时设置分支，子节点[0]=onTrue, [1]=onFalse").apply {
                style = "-fx-text-fill: #888; -fx-font-size: 11px;"
            })
        }
        children.add(Separator())

        // ---- ruleId 下拉选择框 ----
        val ruleIdLabel = Label("规则 (ruleId):")
        val ruleIdCombo = ComboBox<String>().apply {
            maxWidth = Double.MAX_VALUE
        }
        val allRules: List<RuleUiItem> = try {
            ruleRegistry.uiItems()
        } catch (_: Exception) {
            emptyList()
        }
        ruleIdCombo.items.addAll(allRules.map { it.ruleId })
        existing?.ruleId?.let { ruleIdCombo.selectionModel.select(it) }

        // 动态表单容器
        val dynamicFormArea = VBox(6.0)

        // 当 ruleId 改变时，重绘下方动态表单
        ruleIdCombo.selectionModel.selectedItemProperty().addListener { _, _, selectedRuleId ->
            if (selectedRuleId != null) {
                val uiItem = allRules.find { it.ruleId == selectedRuleId }
                dynamicFormArea.children.clear()
                if (uiItem != null) {
                    val currentArgs = existing?.args?.toMutableMap() ?: mutableMapOf()
                    buildDynamicForm(dynamicFormArea, uiItem, nodeId, ruleIdCombo, existing, currentArgs, ruleConfigs)
                }
            }
        }

        children.addAll(
            HBox(8.0).apply {
                alignment = Pos.CENTER_LEFT
                children.addAll(ruleIdLabel, ruleIdCombo)
                HBox.setHgrow(ruleIdCombo, Priority.ALWAYS)
            },
            dynamicFormArea
        )

        // 若已有配置，触发一次初始渲染
        if (!existing?.ruleId.isNullOrEmpty()) {
            val uiItem = allRules.find { it.ruleId == existing.ruleId }
            if (uiItem != null) {
                val currentArgs = existing.args.toMutableMap() ?: mutableMapOf()
                buildDynamicForm(dynamicFormArea, uiItem, nodeId, ruleIdCombo, existing, currentArgs, ruleConfigs)
            }
        }
    }

    private fun buildDynamicForm(
        container: VBox,
        uiItem: RuleUiItem,
        nodeId: String,
        ruleIdCombo: ComboBox<String>,
        existing: RuleConfig?,
        args: MutableMap<String, Any>,
        ruleConfigs: MutableMap<String, RuleConfig>
    ) {
        container.children.clear()

        val grid = GridPane().apply {
            hgap = 8.0
            vgap = 8.0
            padding = Insets(4.0, 0.0, 4.0, 0.0)

            // 设置列约束：第一列（标签）不拉伸，第二列（控件）自动拉伸占满剩余空间
            columnConstraints.addAll(
                ColumnConstraints().apply { hgrow = Priority.NEVER },
                ColumnConstraints().apply { hgrow = Priority.ALWAYS }
            )
        }

        val allSpecs = uiItem.builtInWeightProps + uiItem.fields
        allSpecs.forEachIndexed { row, spec ->
            val label = Label(buildLabel(spec)).apply {
                tooltip = Tooltip(spec.description)
                // 确保标签不会因为控件占据太多空间而被挤压成 "..."
                minWidth = javafx.scene.layout.Region.USE_PREF_SIZE
            }
            val control = buildControl(spec, existing, args)

            // 监听控件改变 -> 更新 ruleConfigs
            attachChangeListener(control, spec, args) {
                val weight = (args["weight"] as? Double) ?: existing?.weight ?: 0.0
                val mismatch = (args["mismatchedWeight"] as? Double) ?: existing?.mismatchedWeight ?: 0.0
                val newConfig = RuleConfig(
                    nodeId = nodeId,
                    ruleId = ruleIdCombo.value ?: "",
                    weight = weight,
                    mismatchedWeight = mismatch,
                    args = args.filterKeys { it != "weight" && it != "mismatchedWeight" }
                )
                ruleConfigs[nodeId] = newConfig
                onRuleConfigChanged?.invoke(nodeId, newConfig)
            }

            grid.add(label, 0, row)
            grid.add(control, 1, row)
            GridPane.setHgrow(control, Priority.ALWAYS)
        }

        container.children.add(grid)
    }

    private fun buildLabel(spec: RuleFieldSpec): String {
        val required = if (spec.constraints.contains(FieldConstraint.Required)) " *" else ""
        return "${spec.name}$required:"
    }

    private fun buildControl(spec: RuleFieldSpec, existing: RuleConfig?, args: MutableMap<String, Any>): Node {
        val currentValue: Any? = when (spec.propertyName) {
            "weight" -> existing?.weight
            "mismatchedWeight" -> existing?.mismatchedWeight
            else -> existing?.args?.get(spec.propertyName)
        }

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
                val combo = ComboBox<String>().apply { maxWidth = Double.MAX_VALUE }
                val options: List<DynamicFieldOption> = try {
                    optionProviders[type.dataSourceId]?.getOptions() ?: emptyList()
                } catch (_: Exception) {
                    emptyList()
                }
                combo.items.addAll(options.map { it.value ?: it.value })
                currentValue?.toString()?.let { combo.selectionModel.select(it) }
                combo
            }

            is FieldType.ListType -> {
                val elementType = type.elementType
                if (elementType is FieldType.SelectType) {
                    // 场景：List + DataSource -> 多选下拉框
                    val menu = MenuButton("请选择...").apply { maxWidth = Double.MAX_VALUE }
                    val content = VBox(5.0).apply { padding = Insets(5.0, 10.0, 5.0, 10.0) }
                    val options = try {
                        optionProviders[elementType.dataSourceId]?.getOptions() ?: emptyList()
                    } catch (_: Exception) {
                        emptyList()
                    }

                    val selectedValues = (currentValue as? List<*>)?.map { it.toString() }?.toSet() ?: emptySet()

                    options.forEach { opt ->
                        val cb = CheckBox(opt.label).apply {
                            userData = opt.value
                            isSelected = selectedValues.contains(opt.value.toString())
                        }
                        content.children.add(cb)
                    }

                    val customItem = CustomMenuItem(content).apply { isHideOnClick = false }
                    menu.items.add(customItem)
                    updateMultiSelectText(menu, content)
                    menu
                } else {
                    // 场景：普通 List -> TextArea (优化解析)
                    val text =
                        if (currentValue is List<*>) currentValue.joinToString("\n") else currentValue?.toString() ?: ""
                    TextArea(text).apply {
                        prefRowCount = 3
                        promptText = "每行一个值"
                        maxWidth = Double.MAX_VALUE
                    }
                }
            }
        }
    }

    private fun updateMultiSelectText(menu: MenuButton, content: VBox) {
        val selectedLabels = content.children.filterIsInstance<CheckBox>()
            .filter { it.isSelected }
            .map { it.text }
        menu.text = if (selectedLabels.isEmpty()) "请选择..." else selectedLabels.joinToString(", ")
    }

    private fun attachChangeListener(
        control: Node,
        spec: RuleFieldSpec,
        args: MutableMap<String, Any>,
        onChanged: () -> Unit
    ) {
        when (control) {
            is TextField -> control.textProperty().addListener { _, _, newVal ->
                val parsed: Any = when (spec.typeStruct) {
                    FieldType.DoubleType -> newVal.toDoubleOrNull() ?: 0.0
                    FieldType.IntType -> newVal.toIntOrNull() ?: 0
                    else -> newVal
                }
                args[spec.propertyName] = parsed
                onChanged()
            }

            is CheckBox -> control.selectedProperty().addListener { _, _, newVal ->
                args[spec.propertyName] = newVal
                onChanged()
            }

            is ComboBox<*> -> control.selectionModel.selectedItemProperty().addListener { _, _, newVal ->
                if (newVal != null) {
                    args[spec.propertyName] = newVal
                    onChanged()
                }
            }

            is TextArea -> control.textProperty().addListener { _, _, newVal ->
                val lines = newVal.lines().filter { it.isNotBlank() }
                val elementType = (spec.typeStruct as? FieldType.ListType)?.elementType
                args[spec.propertyName] = when (elementType) {
                    FieldType.IntType -> lines.mapNotNull { it.toIntOrNull() }
                    FieldType.DoubleType -> lines.mapNotNull { it.toDoubleOrNull() }
                    else -> lines
                }
                onChanged()
            }

            is MenuButton -> {
                val customItem = control.items.firstOrNull() as? CustomMenuItem
                val vBox = customItem?.content as? VBox
                vBox?.children?.filterIsInstance<CheckBox>()?.forEach { cb ->
                    cb.selectedProperty().addListener { _, _, _ ->
                        val selectedValues = vBox.children.filterIsInstance<CheckBox>()
                            .filter { it.isSelected }
                            .map { it.userData }

                        // 转换类型 (例如 List<Int>)
                        val listType = spec.typeStruct as? FieldType.ListType
                        val elementType = listType?.elementType
                        val finalValues =
                            if (elementType is FieldType.SelectType && elementType.valueType is FieldType.IntType) {
                                selectedValues.mapNotNull { it.toString().toIntOrNull() }
                            } else {
                                selectedValues
                            }

                        args[spec.propertyName] = finalValues
                        updateMultiSelectText(control, vBox)
                        onChanged()
                    }
                }
            }
        }
    }

    private fun clearContent() {
        // 保留第一个 title Label
        if (children.size > 1) {
            children.subList(1, children.size).clear()
        }
    }
}
