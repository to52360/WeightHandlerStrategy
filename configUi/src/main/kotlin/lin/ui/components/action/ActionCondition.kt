package lin.ui.components.action

import lin.ui.components.state.EditorPhase
import lin.ui.components.state.EditorState
import lin.ui.components.state.phase

/**
 * 动作的**适用性条件**：开放原子集合 + 组合子（与 `FieldConstraint` / `FieldType` 同构）。
 *
 * 设计目的（`D-DC-004` 修订）：[EditorAction] 的能力**不再靠新增可空字段扩展**——
 * 每新增一种判定，只加一个 sealed 子类型；动作值的结构永远不动，也不会产生「字段之间非法组合」。
 */
sealed interface ActionCondition {

    /** 恒定成立（如「空态也可点」的保存键）。 */
    object Always : ActionCondition

    /** 相位条件：编辑器处于 [allowed] 之一时成立。 */
    data class Phases(val allowed: Set<EditorPhase>) : ActionCondition

    /** 领域守卫：业务自定义谓词（如「仍被卡组引用则不可删除」）。 */
    data class Guard(val predicate: () -> Boolean) : ActionCondition

    // ── 组合子（递归包装，对应 FieldType.ListType 的位置）──

    /** 全部成立（and）。 */
    data class All(val conditions: List<ActionCondition>) : ActionCondition

    /** 任一成立（or）。 */
    data class Any(val conditions: List<ActionCondition>) : ActionCondition

    /** 取反。 */
    data class Not(val condition: ActionCondition) : ActionCondition
}

/** 判定条件是否成立（`when` 穷举 ⇒ 新增子类型时编译器强制补齐）。 */
fun ActionCondition.satisfiedBy(state: EditorState<*>): Boolean = when (this) {
    is ActionCondition.Always -> true
    is ActionCondition.Phases -> state.phase in allowed
    is ActionCondition.Guard -> predicate()
    is ActionCondition.All -> conditions.all { it.satisfiedBy(state) }
    is ActionCondition.Any -> conditions.any { it.satisfiedBy(state) }
    is ActionCondition.Not -> !condition.satisfiedBy(state)
}

/**
 * 动作的**呈现装饰**（可叠加、开放）：由展现器（[EditorActionPresenter]）按类型分派呈现。
 *
 * 同样不靠新增字段扩展——后续要图标 / 快捷键 / 禁用原因提示，加 sealed 子类型即可。
 */
sealed interface ActionDecoration {
    /** 悬浮提示文案。 */
    data class TooltipText(val text: String) : ActionDecoration
}
