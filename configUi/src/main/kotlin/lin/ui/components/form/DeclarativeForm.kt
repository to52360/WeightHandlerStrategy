package lin.ui.components.form

import javafx.scene.control.CheckBox
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.Spinner
import javafx.scene.control.Tooltip
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
    private val defaultValues = mutableMapOf<String, Any?>()

    init {
        for (spec in specs) {
            when (spec) {
                is NumberFieldSpec -> buildNumberField(spec)
                is SelectFieldSpec<*> -> buildSelectField(spec)
                is SwitchGroupSpec -> buildSwitchGroup(spec)
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
    }
}
