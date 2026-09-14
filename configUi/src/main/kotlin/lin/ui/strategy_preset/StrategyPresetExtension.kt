package lin.ui.strategy_preset

import javafx.scene.Node
import lin.ui.UiExtension

/**
 * 策略预设管理 UI 一级导航扩展（T-TG-016）。
 *
 * 位于 Combo 编排 (order=8) / 评估树配置 (order=10) 之后，条件树配置 (order=15) 之前。
 */
class StrategyPresetExtension : UiExtension {

    /** 导航标题常量：跨工作台跳转（WorkbenchNavigator）必须引用本常量，禁止裸写魔法字符串 */
    companion object {
        const val TITLE = "策略预设管理"
    }

    override val title: String = TITLE
    override val order: Int = 12

    override fun createWorkbench(): Node {
        return StrategyPresetWorkbench()
    }

    /** 跨工作台跳转定位：context 为预设 ID（String）时选中该预设 */
    override fun applyContext(workbench: Node, context: Any?) {
        if (workbench is StrategyPresetWorkbench && context is String) {
            workbench.selectPresetById(context)
        }
    }
}
