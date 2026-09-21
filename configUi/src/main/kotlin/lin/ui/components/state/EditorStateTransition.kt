package lin.ui.components.state

/**
 * 编辑器状态**过渡器**：把「状态 → 面板」的驱动定义为**过渡（transition）而非重绘（render）**。
 *
 * ## 为什么需要它
 *
 * 状态机归 store 后，「单一事实源 + 单向数据流」每次状态发射若都重绘面板，会把用户
 * **正在输入的草稿**覆盖掉（表单字段被状态值重置）。改造前的散装实现靠一个
 * `isCreatingMode` 标志位挡着——那正是「同一事实两处维护」状态冗余的源头。
 *
 * ## 语义（两条通道，别自由发挥）
 *
 * - [sync]：**状态驱动** —— 仅在 [EditorState] 的「相位或实体」变化时触发一次 `onTransition`，
 *   同状态重复发射**幂等跳过** ⇒ 草稿不被打断；
 * - [force]：**意图驱动** —— 用户主动动作（点击「新建」等）时强制过渡一次，允许重置草稿
 *   ⇒ 补上幂等跳过的唯一缺口。
 *
 * ⚠️ 机制必须单点：漏写这一步**不会报错**，只会静默丢草稿，故收敛为本类而非各工作台自行比较快照。
 */
class EditorStateTransition<T>(
    private val stateProvider: () -> EditorState<T>,
    private val onTransition: (state: EditorState<T>) -> Unit
) {
    private var lastState: EditorState<T>? = null

    /** 状态驱动：状态未变（相位与实体都相同）则幂等跳过。 */
    fun sync() {
        val current = stateProvider()
        if (current != lastState) {
            lastState = current
            onTransition(current)
        }
    }

    /** 意图驱动：无视幂等，强制过渡一次（用于用户主动重置草稿）。 */
    fun force() {
        val current = stateProvider()
        lastState = current
        onTransition(current)
    }
}
