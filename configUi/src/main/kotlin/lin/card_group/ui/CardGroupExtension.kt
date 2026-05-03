package lin.card_group.ui

import javafx.scene.Node
import javafx.scene.control.Label
import lin.ui.UiExtension

class CardGroupExtension : UiExtension {
    override val title: String = "卡组分组管理"
    override val order: Int = 5 // 排在评估树配置前面

    override fun createWorkbench(): Node {
        // TODO: 卡牌分组可视化的具体实现
        return Label("卡牌分组管理工作台 (CardGroupManagerConfig) (规划中...)").apply {
            style = "-fx-font-size: 18px; -fx-text-fill: #666;"
        }
    }
}
