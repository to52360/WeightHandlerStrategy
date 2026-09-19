package lin.ui.card_group

import javafx.scene.Node
import lin.ui.UiExtension

class CardGroupExtension : UiExtension {

    companion object {
        const val TITLE = "卡组分组管理"
    }

    override val title: String = TITLE
    override val order: Int = 5 // 排在评估树配置前面

    override fun createWorkbench(): Node {
        return CardGroupWorkbench()
    }

    /** 跨工作台跳转定位：context 为方案 ID（String）时选中该方案 */
    override fun applyContext(workbench: Node, context: Any?) {
        if (workbench is CardGroupWorkbench && context is String) {
            workbench.selectManagerById(context)
        }
    }
}
