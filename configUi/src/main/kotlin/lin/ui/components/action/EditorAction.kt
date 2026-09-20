package lin.ui.components.action

import lin.ui.components.state.EditorState

/**
 * 编辑器动作值：与 [UiCapability] 平行，但语义轴不同。
 *
 * - [UiCapability] 的语义轴 = 是否依赖选中项（Standalone / Entity），由 `ResourcePickerBar` 编排；
 * - [EditorAction] 的语义轴 = 依赖编辑器状态机（[EditorState.Empty] / [EditorState.Creating] / [EditorState.Editing]），
 *   由 [EditorActionBar] 编排。
 *
 * 目的：把「保存 / 删除」等编辑器级动作从散装 Button + 回调，抽象为可声明、可组合、可编排的一等值，
 * 让各编辑器面板（Combo / 预设 / 光环）无需每次重想「save 怎么禁用、delete 怎么隐藏」。
 */
sealed interface EditorAction {
    val label: String
    val variant: ActionVariant

    /** 当前状态下是否可点击 */
    fun isEnabled(state: EditorState<*>): Boolean

    /** 当前状态下是否可见 */
    fun isVisible(state: EditorState<*>): Boolean

    /** 执行动作（业务逻辑由 Lambda 闭包承载） */
    fun execute()
}

/**
 * 可写动作：Creating 或 Editing 下可用、始终可见（如「保存」）。
 */
data class MutationAction(
    override val label: String,
    override val variant: ActionVariant = ActionVariant.SUCCESS,
    val handle: () -> Unit
) : EditorAction {
    override fun isEnabled(state: EditorState<*>) = state !is EditorState.Empty
    override fun isVisible(state: EditorState<*>) = true
    override fun execute() = handle()
}

/**
 * 仅编辑态动作：仅 Editing 下可见且可用（如「删除」）。
 */
data class EditingOnlyAction(
    override val label: String,
    override val variant: ActionVariant = ActionVariant.DANGER,
    val handle: () -> Unit
) : EditorAction {
    override fun isEnabled(state: EditorState<*>) = state is EditorState.Editing
    override fun isVisible(state: EditorState<*>) = state is EditorState.Editing
    override fun execute() = handle()
}
