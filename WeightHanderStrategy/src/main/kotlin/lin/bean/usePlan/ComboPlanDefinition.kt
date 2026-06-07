package lin.bean.usePlan

data class ComboPlanDefinition(
    val id: String,
    val coreGroupIds: Set<String>,
    val depGroupIds: Set<String>,
    val score: Double = 0.0,
    /**
     * 硬互斥：同一个 combo 下多个核心候选绝对不能同时进入本轮组合时才开启。
     *
     * 如果只是"不希望同时出，但特殊情况下可以接受"，不要用 coreMutex；
     * 用负 score 表达软惩罚即可，例如 coreGroupIds 和 depGroupIds 指向同一类组。
     */
    val coreMutex: Boolean = true,
    /**
     * 只表达组级使用顺序，不直接绑定具体两张牌。
     */
    val relation: ComboRelation = ComboRelation.SCORE_ONLY,
    /**
     * 暂保留配置字段，当前排序器不消费强相邻语义。
     * 强相邻需要后续单独设计"组块/窗口/重规划"模型。
     */
    val mustAdjacent: Boolean = false
)

enum class ComboRelation {
    SCORE_ONLY,
    CORE_BEFORE_DEP,
    DEP_BEFORE_CORE
}

// ==================== 扩展函数 ====================

/**
 * 按 groupId 直接索引 ComboPlanDefinition，省去中间 CardComboBinding 层。
 * 一张定义归入其所有 core/dep group，供后续 per-card 归并。
 */
internal fun List<ComboPlanDefinition>.indexComboDefByGroupId(): Map<String, List<ComboPlanDefinition>> {
    val index = linkedMapOf<String, MutableList<ComboPlanDefinition>>()
    forEach { definition ->
        (definition.coreGroupIds + definition.depGroupIds).forEach { groupId ->
            index.getOrPut(groupId) { mutableListOf() }.add(definition)
        }
    }
    return index
}

internal fun List<ComboPlanDefinition>.toComboUseBindingByGroupId(): Map<String, List<CardComboUseBinding>> {
    val index = linkedMapOf<String, MutableList<CardComboUseBinding>>()
    forEach { definition ->
        val binding = definition.toUseBinding() ?: return@forEach
        (binding.beforeGroupIds + binding.afterGroupIds).forEach { groupId ->
            index.getOrPut(groupId) { mutableListOf() }.add(binding)
        }
    }
    return index
}

/**
 * 把 combo 定义里的编排关系预解析成运行期可直接使用的组级顺序绑定。
 * SCORE_ONLY 只服务评分，不进入出牌编排。
 */
internal fun ComboPlanDefinition.toUseBinding(): CardComboUseBinding? {
    return when (relation) {
        ComboRelation.SCORE_ONLY -> null
        ComboRelation.CORE_BEFORE_DEP -> CardComboUseBinding(id, coreGroupIds, depGroupIds)
        ComboRelation.DEP_BEFORE_CORE -> CardComboUseBinding(id, depGroupIds, coreGroupIds)
    }
}
