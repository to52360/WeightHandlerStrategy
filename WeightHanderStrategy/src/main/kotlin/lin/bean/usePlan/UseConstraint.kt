package lin.bean.usePlan

/**
 * 使用阶段约束：只影响“已经选中的牌按什么顺序打出去”。
 *
 * 它不决定牌是否入选，也不参与权重加分；UsePlanOrderer 会在本轮 UsePlan 中
 * 把这些约束解析成具体卡牌之间的先后关系。
 */
sealed interface UseConstraint

/**
 * 组级使用顺序约束。
 *
 * combo 定义表达的是“某组牌应先于另一组牌”，具体命中哪几张牌由 UsePlanOrderer
 * 在本轮 UsePlan 中解析，避免在定义阶段把组关系过早打包成两张具体牌。
 */
data class MustUseGroupBefore(
    val beforeGroupIds: Set<String>,
    val afterGroupIds: Set<String>,
    val reason: String
) : UseConstraint
