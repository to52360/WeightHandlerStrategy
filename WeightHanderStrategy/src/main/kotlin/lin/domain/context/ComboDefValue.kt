package lin.domain.context

import lin.config.EngineConfig
import kotlin.math.pow
import kotlin.math.sqrt

// 下面常量的值由 engine.properties 控制，EngineConfig 提供默认值。
// 运行时可通过系统属性或外部配置文件覆盖（见 engine.properties 注释）。

/** 一费权重值，打不满费用时仍然可打出 */
val CostWeight: Double get() = EngineConfig.costWeight
val MaxCostWeight: Double get() = EngineConfig.maxCostWeight
val NotWeight: Double get() = EngineConfig.notWeight
val BaseWeight: Double get() = EngineConfig.baseWeight
val OrderWeight: Double get() = EngineConfig.orderWeight
val UnUseWeight: Double get() = EngineConfig.unUseWeight
val UseSkillWeight: Double get() = EngineConfig.useSkillWeight

val ScoreExponent: Double get() = EngineConfig.scoreExponent
val PenaltyRatioExponent: Double get() = EngineConfig.penaltyRatioExponent
val PenaltyWeight: Double get() = EngineConfig.penaltyWeight
val ComboCardWeight: Double get() = EngineConfig.comboCardWeight
val ComboDecayFactor: Double get() = EngineConfig.comboDecayFactor

// 基础分原语（双轨制轻量费用微增量）：由实际费用派生（0.5 * cost，保守封顶 4.0）
// 用于战术模式下同组打破平局、以及法术底线兜底（见 D-012）
fun baseScore(cost: Int): Double = (CostWeight * cost.toDouble().pow(ScoreExponent)).coerceAtMost(4.0)

// 剩余法力惩罚原语：使用解耦后的 PenaltyWeight (使 PenaltyWeight < CostWeight，防止低费单卡负分)
fun remainingCostPenalty(remainingCost: Int, totalCost: Int): Double {
    if (totalCost <= 0) return 0.0
    val ratio = remainingCost.toDouble() / totalCost.toDouble()
    return PenaltyWeight * sqrt(remainingCost.toDouble()) * ratio.pow(PenaltyRatioExponent)
}

// 组合非线性复杂性惩罚：(n-1)^1.5 * ComboCardWeight，防止多卡垃圾堆砌
fun comboPenalty(cardsCount: Int): Double {
    if (cardsCount <= 1) return 0.0
    return (cardsCount - 1).toDouble().pow(1.5) * ComboCardWeight
}

// 等待/动画时序 (millis)
val AwaitAnimationTime: Long get() = EngineConfig.awaitAnimationTime
val UseAnimationTime: Long get() = EngineConfig.useAnimationTime
val ChangeAnimationTime: Long get() = EngineConfig.changeAnimationTime
val FourAnimationTime: Long get() = EngineConfig.awaitAnimationTime * 4






