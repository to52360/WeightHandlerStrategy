package lin.ui

/**
 * 工作台全局页面跳转与上下文导航器。
 * 供工作台之间进行跨模块联动（如从卡组管理跳转编辑对应的策略预设）。
 */
class WorkbenchNavigator {
    var onNavigate: ((moduleTitle: String, context: Any?) -> Unit)? = null

    fun navigateTo(moduleTitle: String, context: Any? = null) {
        onNavigate?.invoke(moduleTitle, context)
    }
}
