package lin.ui.components.layout

import javafx.scene.control.Label

/**
 * 分段/小节标题：统一字号加粗与深蓝灰色调 (#2c3e50)。
 * 原生继承 Label，避免伪包装，用于各编辑器与配置面板的区块说明。
 */
class SectionTitle(text: String = "") : Label(text) {
    init {
        style = "-fx-font-weight: bold; -fx-text-fill: #2c3e50;"
    }
}
