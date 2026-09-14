package lin.ui

import javafx.scene.Node

/**
 * UI 扩展模块接口
 * 实现该接口并通过 Koin 注册后，主界面会自动将其添加到侧边栏导航中。
 */
interface UiExtension {
    val title: String
    val order: Int get() = 100 // 用于菜单排序

    fun createWorkbench(): Node

    /**
     * 将跨工作台跳转携带的上下文应用到刚创建的工作台实例（如定位选中某条资源）。
     *
     * 壳层（MainShellView）只负责切换工作台，不解释上下文 —— 各扩展自行覆写
     * 解释自己工作台认识的上下文形态，避免导航壳硬编码具体工作台类型。
     */
    fun applyContext(workbench: Node, context: Any?) {}
}
