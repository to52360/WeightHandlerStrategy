package lin.ui.components

import javafx.scene.layout.VBox
import lin.tree_config.ui.LogicNodeType
import lin.tree_config.ui.LogicNodeWrapper

/**
 * 属性面板编辑器策略。
 * 负责为特定 payload 类型渲染 LEAF / BRANCH 节点的属性表单，以及状态写回。
 */
interface PropertyEditorStrategy<L> {
    /**
     * 判断该策略是否能编辑给定类型的节点。
     */
    fun canEdit(type: LogicNodeType): Boolean

    /**
     * 在 panel 容器中渲染属性表单。
     * @param panel 属性面板提供的 VBox 容器
     * @param wrapper 当前选中的节点包装器
     * @param onChanged 表单任何字段变更后调用，通知外部刷新
     */
    fun render(panel: VBox, wrapper: LogicNodeWrapper<L>, onChanged: () -> Unit)
}
