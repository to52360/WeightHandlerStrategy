package lin.ui.card_group.behavior

import javafx.beans.value.ObservableBooleanValue
import javafx.geometry.Insets
import javafx.scene.control.Tab
import javafx.scene.control.TabPane
import javafx.scene.layout.VBox
import lin.ui.card_group.WorkbenchStore

/**
 * 行为编辑 Tab 面板：将行为策略分类编排到不同的 Tab 页中。
 * 适合放置于 GroupBehaviorDialog 弹窗中集中配置。
 */
class BehaviorTabPane(
    private val store: WorkbenchStore,
    disableWhen: ObservableBooleanValue
) {
    val node: TabPane

    private val overridePane = OverridePane(store, disableWhen)
    private val useActionPane = UseActionPane(store, disableWhen)
    private val surplusGatePane = SurplusGatePane(store, disableWhen)

    init {
        val tabPane = TabPane().apply {
            tabClosingPolicy = TabPane.TabClosingPolicy.UNAVAILABLE
        }

        // ── Tab 1：🏷️ 阶段与排序（基础覆盖：阶段/重规划/排序权重）──
        val tab1Content = VBox(14.0).apply {
            padding = Insets(14.0)
            children.addAll(
                overridePane.baseBlock
            )
        }
        val tab1 = Tab("🏷️ 阶段与排序", tab1Content)

        // ── Tab 2：🔀 动态条件阶段 ──
        val tab2Content = VBox(14.0).apply {
            padding = Insets(14.0)
            children.addAll(
                overridePane.conditionalBlock
            )
        }
        val tab2 = Tab("🔀 动态条件阶段", tab2Content)

        // ── Tab 3：⚡ 使用动作与统计维 ──
        val tab3Content = VBox(14.0).apply {
            padding = Insets(14.0)
            children.addAll(
                useActionPane.node
            )
        }
        val tab3 = Tab("⚡ 使用动作", tab3Content)

        // ── Tab 4：🚪 余费门槛（分组级，空闲费 ≥ N 才放行垫牌）──
        val tab4Content = VBox(14.0).apply {
            padding = Insets(14.0)
            children.addAll(
                surplusGatePane.node
            )
        }
        val tab4 = Tab("🚪 余费门槛", tab4Content)

        tabPane.tabs.addAll(tab1, tab2, tab3, tab4)
        node = tabPane
    }
}

typealias BehaviorEditorPane = BehaviorTabPane
