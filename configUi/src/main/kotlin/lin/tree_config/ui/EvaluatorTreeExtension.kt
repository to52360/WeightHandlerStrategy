package lin.tree_config.ui

import javafx.scene.Node
import lin.ui.UiExtension

class EvaluatorTreeExtension : UiExtension {
    override val title: String = "评估树配置"
    override val order: Int = 10

    override fun createWorkbench(): Node {
        return EvaluatorTreeWorkbench()
    }
}
