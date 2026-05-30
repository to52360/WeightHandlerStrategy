package lin.bean.usePlan

data class CardUseConfig(
    val purposeTags: Set<PurposeTag> = emptySet(), // 战略用途：规则/评估语义，不表达真实出牌顺序
    val tags: Set<UseTag> = emptySet(),           // 执行标签：编排/执行语义，不作为规则用途判断
    val stageOverride: UseStage? = null           // 默认留空由 UseIntentDeriver 推导，特例时手动指定
)

/**
 * 运行时的出牌意图，由排序器和执行器消费。
 *
 * UsePlanOrderer 只消费 stage/orderWeight；tags 留给执行器或后续策略识别，
 * 不应在排序器里继续扩散标签分支。
 */
data class UseIntent(
    val stage: UseStage = UseStage.VALUE,
    val tags: Set<UseTag> = emptySet(),
    val orderWeight: Double = 0.0
)
