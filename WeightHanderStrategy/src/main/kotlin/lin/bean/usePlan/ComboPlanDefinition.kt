package lin.bean.usePlan

data class ComboPlanDefinition(
    val id: String,
    val coreGroupIds: Set<String>,
    val depGroupIds: Set<String>,
    val score: Double = 0.0,
    /**
     * 起手换牌专用的**组合协同加分**：本 combo 的核心组与依赖组在起手同时保留时，
     * 给保留子集额外加一次该分值——表达「A、B 单留都一般，一起留才值钱」
     * （单卡 `changeWeight` 是独立通道，只能表达单卡价值，表达不了组合溢价）。
     *
     * **单一作用**：只被 `ChangeCardSelector` 消费，**绝不参与出牌评分**。
     * 出牌侧的协同加分是 [score]，两者互不相关、不可互相替代。
     *
     * **默认值 0.0 = 不加成**（与「未配置」运行时行为等价，是该加法的单位元），
     * 故不需要可空/哨兵值——存在第三态（如卡组级默认起手协同分）时再改成 `Double?`。
     *
     * 协同判定复用出牌侧既有语义：`counterpart` 齐备即命中（见 `CardComboEntry.counterpartGroupIds`）。
     */
    val changeScore: Double = 0.0,
    /**
     * 硬互斥：同一个 combo 下多个核心候选绝对不能同时进入本轮组合时才开启。
     *
     * 如果只是"不希望同时出，但特殊情况下可以接受"，不要用 coreMutex；
     * 用负 score 表达软惩罚即可，例如 coreGroupIds 和 depGroupIds 指向同一类组。
     *
     * ⚠️ 该值同时被起手换牌消费（ChangeCardSelector.hasCoreMutexConflict 借同一字段
     * 做「不能同时保留」约束）。置 false 会连带取消起手侧互斥——
     * 「出牌不互斥但起手要互斥」的诉求当前无法表达（见 Q-010）。
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
