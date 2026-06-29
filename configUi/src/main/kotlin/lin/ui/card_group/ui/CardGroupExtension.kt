package lin.ui.card_group.ui

import javafx.scene.Node
import lin.ui.UiExtension

class CardGroupExtension : UiExtension {
    override val title: String = "卡组分组管理"
    override val order: Int = 5 // 排在评估树配置前面

    override fun createWorkbench(): Node {
        return CardGroupWorkbench()
    }
}
