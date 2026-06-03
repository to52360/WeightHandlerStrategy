package lin.combo_plan.ui

import javafx.scene.Node
import lin.ui.UiExtension

class ComboPlanExtension : UiExtension {
    override val title: String = "Combo 编排配置"
    override val order: Int = 8

    override fun createWorkbench(): Node {
        return ComboPlanWorkbench()
    }
}
