package lin.ui.components.form

import javafx.scene.control.*
import javafx.scene.layout.VBox
import javafx.util.StringConverter
import lin.ui.components.layout.FormField

/**
 * 表单项纯数据声明规范（能力值化，纯数据与函数作为值）
 */
sealed interface FormItemSpec

/**
 * 数值微调输入项规格
 */
data class NumberFieldSpec(
    val id: String,
    val label: String,
    val min: Double = -100.0,
    val max: Double = 100.0,
    val default: Double = 0.0,
    val step: Double = 0.5,
    val tooltip: String? = null
) : FormItemSpec

/**
 * 下拉单选规格
 */
data class SelectFieldSpec<T>(
    val id: String,
    val label: String,
    val options: List<T>,
    val default: T,
    val display: (T) -> String = { it.toString() }
) : FormItemSpec

/**
 * 开关/复选框规格
 */
data class SwitchFieldSpec(
    val id: String,
    val label: String,
    val default: Boolean = false,
    val tooltip: String? = null,
    val isDisabled: Boolean = false
)

/**
 * 文本输入项规格（T-DC-010）：`id`/`label` 必填，`prompt`/`constraints` 开放缺省——
 * 与 [lin.ui.components.action.EditorAction]「3 必填 + 开放列表」模式同构。
 */
data class TextFieldSpec(
    val id: String,
    val label: String,
    val prompt: String? = null,
    val constraints: List<FieldConstraint> = emptyList()
) : FormItemSpec

/**
 * 文本字段约束：开放原子集合 + 组合子（与 [lin.ui.components.action.ActionCondition] 同构）。
 *
 * 每新增一种判定 = 加一个 sealed 子类型，规格结构永不再改；失败文案随原子数据携带。
 * 求值为纯函数（不触碰 JavaFX 控件），失败返回文案、满足返回 null。
 */
sealed interface FieldConstraint {

    /** 必填：raw 去空格后为空则失败。 */
    data class Required(val message: String = "该字段不能为空") : FieldConstraint

    /** 数字格式：非空但解析为 Double 失败则失败（空由 Required 负责）。 */
    data class Decimal(val message: String = "请输入有效数字") : FieldConstraint

    /** 最大长度。 */
    data class MaxLength(val max: Int, val message: String = "最多 $max 字符") : FieldConstraint

    // ── 组合子（递归包装，对应 ActionCondition.All/Any/Not 的位置）──

    /** 全部成立（and）：任一失败即返回首个失败文案。 */
    data class All(val constraints: List<FieldConstraint>) : FieldConstraint

    /** 任一成立（or）：全部失败时返回首个失败文案。 */
    data class Any(val constraints: List<FieldConstraint>) : FieldConstraint

    /** 取反：内层失败（满足）则通过，内层通过则返回本约束文案。 */
    data class Not(val constraint: FieldConstraint, val message: String = "不满足条件") : FieldConstraint
}

/** 纯函数求值：满足返回 null，不满足返回失败文案（单测 headless 可跑）。 */
fun FieldConstraint.evaluate(raw: String): String? = when (this) {
    is FieldConstraint.Required -> if (raw.isBlank()) message else null
    is FieldConstraint.Decimal -> if (raw.isNotBlank() && raw.toDoubleOrNull() == null) message else null
    is FieldConstraint.MaxLength -> if (raw.length > max) message else null
    is FieldConstraint.All -> constraints.firstNotNullOfOrNull { it.evaluate(raw) }
    is FieldConstraint.Any -> {
        val failures = constraints.mapNotNull { it.evaluate(raw) }
        if (failures.size == constraints.size) failures.firstOrNull() else null
    }

    is FieldConstraint.Not -> if (constraint.evaluate(raw) == null) message else null
}

/**
 * 校验问题（纯数据，供呈现端 [FormPrompt] 消费）。
 */
data class FieldProblem(
    val fieldId: String,
    val label: String,
    val message: String
)

/**
 * 纯逻辑聚合校验：对单个字段的 raw 文本求值全部约束，产出问题列表（不触碰控件）。
 * 供 `DeclarativeForm.validate()` 及脱离表单控件的独立输入（如克隆弹窗）复用。
 */
fun evaluateField(
    fieldId: String,
    label: String,
    constraints: List<FieldConstraint>,
    raw: String
): List<FieldProblem> =
    constraints.mapNotNull { constraint ->
        constraint.evaluate(raw)?.let { FieldProblem(fieldId, label, it) }
    }

/**
 * 开关卡片分组规格
 */
data class SwitchGroupSpec(
    val title: String,
    val switches: List<SwitchFieldSpec>
) : FormItemSpec

/**
 * 常用表单规格预设模板（纯数据能力值化，杜绝业务端重复手写 min/max/step 样板）
 */
object FormSpecs {

    /**
     * 预设规格：标准策略费用与加权评分微调 (-100.0 ~ 100.0, 步长 0.5)
     */
    fun score(
        id: String,
        label: String,
        default: Double = 0.0,
        tooltip: String? = null
    ): NumberFieldSpec = NumberFieldSpec(
        id = id,
        label = label,
        min = -100.0,
        max = 100.0,
        default = default,
        step = 0.5,
        tooltip = tooltip
    )
}

/**
 * 全项目通用的声明式表单组件（DeclarativeForm）：
 * 彻底消灭在各个面板类体中平铺散落控件（Spinner/ComboBox/CheckBox）的反模式。
 * 接收纯数据 Specs 自动完成控件渲染、排版对齐与 Tooltip 挂载，提供统一基于字段 ID 的安全存取与一键重置。
 */
class DeclarativeForm(
    val specs: List<FormItemSpec>,
    val defaultLabelWidth: Double = 135.0,
    spacing: Double = 10.0
) : VBox(spacing) {

    private val numberSpinners = mutableMapOf<String, Spinner<Double>>()
    private val selectCombos = mutableMapOf<String, ComboBox<Any?>>()
    private val selectSpecs = mutableMapOf<String, SelectFieldSpec<*>>()
    private val switchChecks = mutableMapOf<String, CheckBox>()
    private val textFields = mutableMapOf<String, TextField>()
    private val textSpecs = mutableMapOf<String, TextFieldSpec>()
    private val defaultValues = mutableMapOf<String, Any?>()

    init {
        for (spec in specs) {
            when (spec) {
                is NumberFieldSpec -> buildNumberField(spec)
                is SelectFieldSpec<*> -> buildSelectField(spec)
                is SwitchGroupSpec -> buildSwitchGroup(spec)
                is TextFieldSpec -> buildTextField(spec)
            }
        }
    }

    private fun buildNumberField(spec: NumberFieldSpec) {
        val spinner = Spinner<Double>(spec.min, spec.max, spec.default, spec.step).apply {
            isEditable = true
            prefWidth = 140.0
            if (!spec.tooltip.isNullOrBlank()) {
                tooltip = Tooltip(spec.tooltip)
            }
        }
        numberSpinners[spec.id] = spinner
        defaultValues[spec.id] = spec.default
        children.add(FormField(spec.label, spinner, defaultLabelWidth))
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> buildSelectField(spec: SelectFieldSpec<T>) {
        val combo = ComboBox<Any?>().apply {
            items.addAll(spec.options as List<Any?>)
            value = spec.default
            prefWidth = 140.0
            converter = object : StringConverter<Any?>() {
                override fun toString(obj: Any?): String =
                    if (obj == null) "" else spec.display(obj as T)

                override fun fromString(string: String?): Any? = null
            }
        }
        selectCombos[spec.id] = combo
        selectSpecs[spec.id] = spec
        defaultValues[spec.id] = spec.default
        children.add(FormField(spec.label, combo, defaultLabelWidth))
    }

    private fun buildSwitchGroup(group: SwitchGroupSpec) {
        val box = VBox(8.0).apply {
            style =
                "-fx-border-color: #dee2e6; -fx-border-radius: 6px; -fx-padding: 10px; -fx-background-color: #ffffff; -fx-background-radius: 6px;"
            if (group.title.isNotBlank()) {
                children.add(Label(group.title).apply {
                    style = "-fx-font-weight: bold; -fx-text-fill: #34495e; -fx-padding: 0 0 4 0;"
                })
            }
            for (sw in group.switches) {
                val cb = CheckBox(sw.label).apply {
                    isSelected = sw.default
                    if (sw.isDisabled) {
                        isDisable = true
                    }
                    if (!sw.tooltip.isNullOrBlank()) {
                        tooltip = Tooltip(sw.tooltip)
                    }
                }
                switchChecks[sw.id] = cb
                defaultValues[sw.id] = sw.default
                children.add(cb)
            }
        }
        children.add(box)
    }

    private fun buildTextField(spec: TextFieldSpec) {
        val field = TextField().apply {
            if (!spec.prompt.isNullOrBlank()) {
                promptText = spec.prompt
            }
        }
        textFields[spec.id] = field
        textSpecs[spec.id] = spec
        children.add(FormField(spec.label, field, defaultLabelWidth))
    }

    /** 读取数值字段 */
    fun getNumber(id: String): Double =
        numberSpinners[id]?.value ?: (defaultValues[id] as? Double) ?: 0.0

    /** 写入数值字段 */
    fun setNumber(id: String, value: Double) {
        numberSpinners[id]?.valueFactory?.value = value
    }

    /** 读取下拉选择项 */
    @Suppress("UNCHECKED_CAST")
    fun <T> getSelect(id: String): T =
        selectCombos[id]?.value as? T ?: defaultValues[id] as T

    /** 写入下拉选择项 */
    fun <T> setSelect(id: String, value: T) {
        selectCombos[id]?.value = value
    }

    /** 读取布尔/开关字段 */
    fun getBoolean(id: String): Boolean =
        switchChecks[id]?.isSelected ?: (defaultValues[id] as? Boolean) ?: false

    /** 写入布尔/开关字段 */
    fun setBoolean(id: String, value: Boolean) {
        switchChecks[id]?.isSelected = value
    }

    /** 读取文本字段 */
    fun getText(id: String): String = textFields[id]?.text.orEmpty()

    /** 写入文本字段 */
    fun setText(id: String, value: String) {
        textFields[id]?.text = value
    }

    /**
     * 校验带约束的文本字段：委托纯函数求值（[evaluateField]），产出问题列表。
     * 呈现由 [FormPrompt] 单点负责，本方法不触碰任何弹窗。
     */
    fun validate(): List<FieldProblem> =
        textSpecs.entries.flatMap { (id, spec) ->
            evaluateField(id, spec.label, spec.constraints, textFields[id]?.text.orEmpty())
        }

    /** 一键重置为声明的默认值 */
    @Suppress("UNCHECKED_CAST")
    fun reset() {
        for ((id, spinner) in numberSpinners) {
            spinner.valueFactory.value = defaultValues[id] as? Double ?: 0.0
        }
        for ((id, combo) in selectCombos) {
            combo.value = defaultValues[id]
        }
        for ((id, cb) in switchChecks) {
            cb.isSelected = defaultValues[id] as? Boolean ?: false
        }
        for ((_, field) in textFields) {
            field.clear()
        }
    }
}
