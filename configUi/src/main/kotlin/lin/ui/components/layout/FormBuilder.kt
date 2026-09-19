package lin.ui.components.layout

import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.layout.VBox

/**
 * 轻量表单构建器：基于 JavaFX 原生节点组织表单行与开关卡片。
 * 不引入反射、元数据绑定或复杂代理，仅提供整齐的排版规整能力。
 */
class FormBuilder(
    private val defaultLabelWidth: Double = 135.0,
    spacing: Double = 10.0
) {
    private val container = VBox(spacing)

    /**
     * 添加标准表单字段行：[标签 (固定对齐宽度)] [控件节点 (自动拉伸)]
     */
    fun field(label: String, control: Node, labelWidth: Double = defaultLabelWidth): FormBuilder {
        container.children.add(FormField(label, control, labelWidth))
        return this
    }

    /**
     * 添加开关/复选框分组区块：浅灰色卡片边框收拢样式
     */
    fun switchGroup(title: String? = null, vararg switches: Node): FormBuilder {
        val box = VBox(8.0).apply {
            style = "-fx-border-color: #dee2e6; -fx-border-radius: 6px; -fx-padding: 10px; -fx-background-color: #ffffff; -fx-background-radius: 6px;"
            if (!title.isNullOrBlank()) {
                children.add(Label(title).apply {
                    style = "-fx-font-weight: bold; -fx-text-fill: #34495e; -fx-padding: 0 0 4 0;"
                })
            }
            children.addAll(*switches)
        }
        container.children.add(box)
        return this
    }

    /**
     * 插入小节标题行
     */
    fun section(title: String): FormBuilder {
        container.children.add(SectionTitle(title))
        return this
    }

    /**
     * 插入任意原生控件/布局节点
     */
    fun add(node: Node): FormBuilder {
        container.children.add(node)
        return this
    }

    /**
     * 构建为 VBox 根节点
     */
    fun build(): VBox = container
}

/**
 * 便捷 DSL 函数，用于流式构建轻量表单
 */
inline fun buildForm(
    defaultLabelWidth: Double = 135.0,
    spacing: Double = 10.0,
    builderAction: FormBuilder.() -> Unit
): VBox = FormBuilder(defaultLabelWidth, spacing).apply(builderAction).build()
