package lin.condition_tree.ui.action

import lin.condition_tree.ui.ConditionTreeWorkbench

/**
 * 条件树工作台动作扩展接口。
 * 实现该接口并通过 Koin 注册后，对应的按钮会自动出现在条件树工作台的工具栏中。
 */
interface ConditionTreeWorkbenchAction {
    val title: String
    val order: Int get() = 100

    fun execute(workbench: ConditionTreeWorkbench)
}
