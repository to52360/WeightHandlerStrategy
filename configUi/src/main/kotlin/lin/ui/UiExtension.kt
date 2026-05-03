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
}
