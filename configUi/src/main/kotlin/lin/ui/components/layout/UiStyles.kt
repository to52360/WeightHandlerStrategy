package lin.ui.components.layout

import javafx.scene.control.Button
import javafx.scene.layout.Region
import lin.ui.components.action.ActionVariant

/**
 * 全项目通用样式令牌与扩展函数：
 * 消除业务代码中散落硬编码的内联 CSS 字符串与十六进制色值。
 */
object UiStyles {
    /** 编辑器详情面板外层容器标准边框与背景 */
    const val EDITOR_CONTAINER =
        "-fx-background-color: #fafafa; -fx-border-color: #dee2e6; -fx-border-radius: 8px; -fx-background-radius: 8px;"
}

/**
 * 为容器组件统一应用标准编辑器面板卡片样式
 */
fun Region.applyEditorContainerStyle() {
    style = UiStyles.EDITOR_CONTAINER
}

/**
 * 规范化详情面板底部主要操作按钮（统一 14px 加粗、Padding 10px、拉伸充满）
 */
fun Button.applyPrimaryAction(variant: ActionVariant, action: () -> Unit) {
    maxWidth = Double.MAX_VALUE
    style = "${variant.cssStyle} -fx-font-size: 14px; -fx-font-weight: bold; -fx-padding: 10px;"
    setOnAction { action() }
}
