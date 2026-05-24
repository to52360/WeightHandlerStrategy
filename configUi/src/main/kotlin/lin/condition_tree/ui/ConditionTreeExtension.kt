package lin.condition_tree.ui

import javafx.scene.Node
import lin.ui.UiExtension

class ConditionTreeExtension : UiExtension {
    override val title: String = "条件树配置"
    override val order: Int = 15

    override fun createWorkbench(): Node {
        return ConditionTreeWorkbench()
    }
}
