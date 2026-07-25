package lin.ui.tree_config.action

import lin.ui.tree_config.EvaluatorTreeWorkbench

/**
 * 评估树工作台动作扩展接口。
 * 实现该接口并通过 Koin 注册后，对应的按钮会自动出现在评估树工作台的工具栏中。
 */
interface TreeWorkbenchAction {
    val title: String
    val order: Int get() = 100

    /**
     * 当按钮被点击时执行
     * @param workbench 传入工作台实例，方便 Action 读取当前选中的配置、节点等上下文信息
     */
    fun execute(workbench: EvaluatorTreeWorkbench)
}
