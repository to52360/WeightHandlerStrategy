package lin.bean.usePlan

data class CardUseConfig(
    val purposeTags: Set<PurposeTag> = emptySet(), // 战略用途（规则可见）
    val tags: Set<UseTag> = emptySet(),           // 物理使用标签
    val stageOverride: UseStage? = null           // 默认留空由引擎自动推导，特例时手动指定
)

/**
 * 运行时的出牌意图，由排序器和执行器消费。
 */
data class UseIntent(
    val stage: UseStage = UseStage.VALUE,
    val tags: Set<UseTag> = emptySet(),
    val orderWeight: Double = 0.0
)