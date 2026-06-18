package lin.rule.score

import lin.rule.orthogonal.TransformCall

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
     * 支持级联 Transforms。
     *
     * @param sourceId 数据源 ID（如 "minion_hp"、"my_attack"），由 DataSource 注册表解析
     * @param transforms 链式转换器步骤
     * @param operatorId 评分算子 ID（如 "linear"、"identity"），由 ScoreOperator 注册表解析
     * @param operatorArgs 评分算子的专用参数
     * @param missValue 条件未命中时的分值，默认 0.0
     */
    data class SourceScore(
        val sourceId: String,
        val transforms: List<TransformCall> = emptyList(),
        val operatorId: String,
        val operatorArgs: Map<String, Any> = emptyMap(),
        val missValue: Double = 0.0
    ) : ScoreEffect
}
