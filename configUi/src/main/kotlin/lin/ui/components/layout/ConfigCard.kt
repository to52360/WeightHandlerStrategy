package lin.ui.components.layout

import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox

/**
 * 表单字段行装饰器：[Label 文本] [内容节点]
 * 将控件与字段标签的对齐排版进行规范封装。
 */
class FormField(
    label: String,
    content: Node,
    labelWidth: Double? = null
) : HBox(8.0) {
    init {
        alignment = Pos.CENTER_LEFT
        val labelNode = Label(label).apply {
            style = "-fx-font-weight: bold;"
            if (labelWidth != null) {
                prefWidth = labelWidth
            }
        }
        HBox.setHgrow(content, Priority.ALWAYS)
        children.addAll(labelNode, content)
    }
}

/**
 * 通用配置卡片容器：
 * 提供白底圆角边框、分组标题、辅助说明与内容节点的正交装饰封装。
 */
class ConfigCard(
    title: String,
    description: String? = null,
    content: Node
) : VBox(8.0) {
    init {
        padding = Insets(12.0)
        style = "-fx-background-color: #ffffff; -fx-border-color: #dee2e6; -fx-border-radius: 6; -fx-background-radius: 6;"

        val titleLabel = Label(title).apply {
            style = "-fx-font-weight: bold; -fx-font-size: 13px; -fx-text-fill: #495057;"
        }
        children.add(titleLabel)

        if (!description.isNullOrBlank()) {
            val descLabel = Label(description).apply {
                style = "-fx-text-fill: #6c757d; -fx-font-size: 11px;"
                isWrapText = true
            }
            children.add(descLabel)
        }

        children.add(content)
    }
}

/** 扩展函数：支持链式构建表单行 */
fun Node.asFormField(label: String, labelWidth: Double? = null): FormField =
    FormField(label, this, labelWidth)

/** 扩展函数：支持链式构建卡片区块 */
fun Node.asConfigCard(title: String, description: String? = null): ConfigCard =
    ConfigCard(title, description, this)
