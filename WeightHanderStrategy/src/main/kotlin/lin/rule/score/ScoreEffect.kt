package lin.rule.score

/**
 * 评分效应只描述“如何产生分数”，不负责判断是否触发。
 * Guard 条件由调用方先判断，命中后再执行这里的评分逻辑。
 */
sealed interface ScoreEffect {
    /**
     * 固定分值：条件命中时直接返回固定分数。
     * 示例：ConstantScore(3.0) → 命中就给 3 分，不依赖任何外部数据。
     */
    data class ConstantScore(
        val value: Double,
        val missValue: Double = 0.0
    ) : ScoreEffect

    /**
     * 数据源评分：从 [sourceId] 对应 DataSource 取值，经 [operatorId] 对应 ScoreOperator 转换为分数。
     *
     * @param sourceId 数据源 ID（如 "minion_hp"、"my_attack"），由 DataSource 注册表解析
     * @param operatorId 评分算子 ID（如 "linear"、"identity"），由 ScoreOperator 注册表解析
     * @param missValue 条件未命中时的分值，默认 0.0
     * @param args 算子参数，由算子自身的 [paramSpecs] 定义（如 linear 需要 factor/offset）
     *
     * 示例：SourceScore("minion_hp", "linear", missValue = -1.0, mapOf("factor" to 0.5))
     *   → 命中：取随从血量，用 linear 算子算分 → score = hp × 0.5；未命中：-1.0
     */
    data class SourceScore(
        val sourceId: String,
        val operatorId: String,
        val missValue: Double = 0.0,
        val args: Map<String, Any> = emptyMap()
    ) : ScoreEffect
}
