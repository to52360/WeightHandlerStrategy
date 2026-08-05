package lin.ui.aura_boost

import javafx.scene.Node
import lin.ui.UiExtension

class AuraBoostExtension : UiExtension {
    override val title: String = "光环广播 (AuraBoost)"
    override val order: Int = 18

    override fun createWorkbench(): Node {
        return AuraBoostWorkbench()
    }
}
