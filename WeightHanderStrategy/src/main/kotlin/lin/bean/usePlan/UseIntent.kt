package lin.bean.usePlan

data class CardUseConfig(
    val purposeTags: Set<PurposeTag> = emptySet(), // 战略用途：规则/评估语义，不表达真实出牌顺序
    val stageOverride: UseStage? = null,          // 默认留空由 UseIntentDeriver 推导，特例时手动指定
    val replanAfterUse: Boolean = false,          // 使用后是否需要重新规划
    val orderWeight: Double = 0.0                 // 同阶段内的人工排序偏好
)

/**
 * 运行时的出牌意图，由排序器和执行器消费。
 *
 * UsePlanOrderer 只消费 stage/orderWeight。
 * 执行后重规划这类行为用显式字段表达，不再通过标签间接解释。
 */
data class UseIntent(
    val stage: UseStage = UseStage.VALUE,
    val replanAfterUse: Boolean = false,
    val orderWeight: Double = 0.0
)
