package lin.ui.components.action

import lin.ui.components.state.EditorPhase
import lin.ui.components.state.EditorState

/** 默认可用条件：非空态可用（Creating / Editing）。 */
private val NON_EMPTY = listOf(
    ActionCondition.Phases(setOf(EditorPhase.CREATING, EditorPhase.EDITING))
)

/** 仅编辑态成立（可用与可见同用）。 */
private val EDITING_ONLY = listOf(ActionCondition.Phases(setOf(EditorPhase.EDITING)))

/**
 * 编辑器动作值：与 [UiCapability] 平行，但语义轴不同。
 *
 * - [UiCapability] 的语义轴 = 是否依赖选中项（Standalone / Entity）；
 * - [EditorAction] 的语义轴 = 编辑器状态相位（[EditorPhase]），由 [EditorActionBar] 编排。
 *
 * ## 结构（可扩展性约定）
 *
 * 本类型只有 **3 个必填数据字段**（label / variant / handle）+ **两条开放列表**：
 * - [enabledWhen] / [visibleWhen]：适用性条件，**条件类型是封闭的 sealed、条件是开放的列表**；
 * - [decorations]：呈现装饰，同为开放列表。
 *
 * ⚠️ **禁止**用「再加一个可空字段」扩展能力（如曾有的 `enabledIn` / `visibleIn` / `tooltip`）——
 * 新增一种判定就加一个 [ActionCondition] 子类型、新增一种呈现就加一个 [ActionDecoration] 子类型，
 * 本结构永不改动，也不会产生「字段之间互相矛盾」的非法组合。
 */
data class EditorAction(
    val label: String,
    val variant: ActionVariant = ActionVariant.PRIMARY,
    /** 可用条件：全部成立才可用（默认 = 非空态可用） */
    val enabledWhen: List<ActionCondition> = NON_EMPTY,
    /** 可见条件：全部成立才可见（默认 = 空列表 = 恒可见） */
    val visibleWhen: List<ActionCondition> = emptyList(),
    /** 呈现装饰（可叠加） */
    val decorations: List<ActionDecoration> = emptyList(),
    /** 执行动作（业务逻辑由 Lambda 闭包承载） */
    val handle: () -> Unit
) {
    /** 当前状态下是否可点击。⚠️「不可见即不可用」由此处天然保证，无需静态断言。 */
    fun isEnabled(state: EditorState<*>): Boolean =
        isVisible(state) && enabledWhen.all { it.satisfiedBy(state) }

    /** 当前状态下是否可见。 */
    fun isVisible(state: EditorState<*>): Boolean =
        visibleWhen.all { it.satisfiedBy(state) }

    companion object {
        /** 可写动作：Creating / Editing 可用、始终可见（如「保存」）。 */
        fun mutation(
            label: String,
            variant: ActionVariant = ActionVariant.SUCCESS,
            handle: () -> Unit
        ): EditorAction = EditorAction(label = label, variant = variant, handle = handle)

        /** 仅编辑态动作：仅 Editing 可见且可用（如「删除」）。 */
        fun editingOnly(
            label: String,
            variant: ActionVariant = ActionVariant.DANGER,
            handle: () -> Unit
        ): EditorAction = EditorAction(
            label = label,
            variant = variant,
            enabledWhen = EDITING_ONLY,
            visibleWhen = EDITING_ONLY,
            handle = handle
        )
    }
}
