package lin.ui.components.action

import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Tooltip
import javafx.scene.layout.HBox
import javafx.scene.layout.Pane
import javafx.scene.layout.VBox
import lin.ui.components.layout.applyPrimaryAction

/**
 * 编辑器动作的**展现器**：把「造按钮 + 排列」从解析层（[EditorActionBar]）中剥离（`K-DC-002`）。
 *
 * 分层：
 * - [EditorActionBar]（解析层）= 读动作值 → 请展现器造节点 → 按相位集合驱动 `isDisable` / `isVisible`；
 * - [EditorActionPresenter]（展现层）= 节点形态（样式 / tooltip / 图标）与容器排列。
 *
 * ⚠️ 刻意**不**在 [EditorActionBar] 里加 `horizontal: Boolean` 之类的开关——那等于把布局分支塞回解析层；
 * 换布局 = 换一个展现器实现，业务声明侧零改动。
 */
interface EditorActionPresenter {

    /** 造一个可点击节点。默认实现 = 标准按钮（语义配色 + 装饰分派）。 */
    fun node(action: EditorAction, onExecute: () -> Unit): Node = Button(action.label).apply {
        // 复用样式单点（applyPrimaryAction），不内联 CSS 字符串
        applyPrimaryAction(action.variant, onExecute)
        // 装饰按类型分派呈现（新增装饰类型时在此补分支，动作值结构不动）
        action.decorations.forEach { decoration ->
            when (decoration) {
                is ActionDecoration.TooltipText -> tooltip = Tooltip(decoration.text)
            }
        }
    }

    /** 把节点集合排成可嵌入的容器。 */
    fun arrange(nodes: List<Node>): Pane
}

/** 垂直堆叠（默认）：窄侧栏场景，如 Combo 编排编辑器。 */
class StackedActionPresenter(private val spacing: Double = 8.0) : EditorActionPresenter {
    override fun arrange(nodes: List<Node>): Pane = VBox(spacing).apply {
        children.addAll(nodes)
    }
}

/** 横排：卡片底部 / 宽面板按钮栏场景（如光环编辑器、预设详情面板）。 */
class InlineActionPresenter(
    private val spacing: Double = 8.0,
    private val alignment: Pos = Pos.CENTER_LEFT
) : EditorActionPresenter {
    override fun arrange(nodes: List<Node>): Pane {
        // ⚠️ 不要写成 `HBox(spacing).apply { this.alignment = alignment }`：隐式接收者会遮蔽同名构造参数
        //    ⇒ 变成 `this.alignment = this.alignment` 自赋值，对齐静默失效（Kotlin 只报"参数未使用"警告）。
        val box = HBox(spacing)
        box.alignment = alignment
        box.children.addAll(nodes)
        return box
    }
}
