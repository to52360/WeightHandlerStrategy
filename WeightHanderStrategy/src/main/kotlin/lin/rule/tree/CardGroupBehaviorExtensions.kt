package lin.rule.tree

import lin.bean.usePlan.GroupUseOverride

/** 在 [CardGroupBehavior] 列表上的便捷操作，供 UI 与持久层复用。 */
fun List<CardGroupBehavior>.findOverride(): GroupUseOverride? =
    firstOrNull { it is CardGroupBehavior.OverrideBehavior }?.let { (it as CardGroupBehavior.OverrideBehavior).override }

fun List<CardGroupBehavior>.findUseActionBehavior(): CardGroupBehavior.UseActionBehavior? =
    firstOrNull { it is CardGroupBehavior.UseActionBehavior } as? CardGroupBehavior.UseActionBehavior

fun List<CardGroupBehavior>.findUseActions(): List<String> =
    findUseActionBehavior()?.useActions ?: emptyList()

fun List<CardGroupBehavior>.findExtraConfig(): Map<String, Any> =
    findUseActionBehavior()?.extraConfig ?: emptyMap()

/** 以不可变方式写入/移除 OVERRIDE 行为：override 为 null 或默认值时移除该行。 */
fun List<CardGroupBehavior>.withOverride(override: GroupUseOverride?): List<CardGroupBehavior> {
    val without = filter { it !is CardGroupBehavior.OverrideBehavior }
    return if (override == null || override.isDefault()) without
    else without + CardGroupBehavior.OverrideBehavior(override)
}

/** 以不可变方式写入/移除 USE_ACTION 行为：actions 和 extraConfig 均为空时移除该行。 */
fun List<CardGroupBehavior>.withUseActions(
    actions: List<String>,
    extraConfig: Map<String, Any> = emptyMap()
): List<CardGroupBehavior> {
    val without = filter { it !is CardGroupBehavior.UseActionBehavior }
    return if (actions.isEmpty() && extraConfig.isEmpty()) without
    else without + CardGroupBehavior.UseActionBehavior(actions, extraConfig)
}
