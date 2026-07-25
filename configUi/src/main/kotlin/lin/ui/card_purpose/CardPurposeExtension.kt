package lin.ui.card_purpose

import javafx.scene.Node
import lin.ui.UiExtension

class CardPurposeExtension : UiExtension {
    override val title: String = "卡牌用途配置"
    override val order: Int = 7

    override fun createWorkbench(): Node {
        return CardPurposeWorkbench()
    }
}
