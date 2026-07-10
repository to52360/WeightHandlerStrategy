package lin.domain.context

import kotlin.math.pow
import kotlin.math.sqrt

//表示一费5点权重,可以在打不满费用时打出
const val CostWeight = 5.0
const val MaxCostWeight = 10.0
const val NotWeight = 0.0
const val BaseWeight = 1.0
const val OrderWeight = 1.0
const val UnUseWeight = -100.0
const val UseSkillWeight = -7.0

// 基础分凹度指数：α∈(0,1)，越小越凹（边际递减越猛）。
// α=0.5 等价于 √cost；α=0.7 高费卡价值更高；Q-3 校准时可调。
const val ScoreExponent = 0.5

// 惩罚占比敏感度：β∈[0,1]，控制"浪费法力占比"对惩罚的影响力。
// β=0 → 不关心占比（退回纯绝对惩罚）；β=0.5 → 均衡；β=1 → 完全按占比缩放。
const val PenaltyRatioExponent = 0.5

// 基础分原语：由静态费用派生，凹函数（边际费用价值递减，见 scoring-model/TRACKER.md Q-2）。
// ScoreExponent=0.5 时：1费→5, 5费→11.2, 10费→15.8。所有卡恒有基础分，与额外分(配置溢价)/规则分正交相加。
fun baseScore(cost: Int): Double = CostWeight * cost.toDouble().pow(ScoreExponent)

// 剩余法力惩罚原语：绝对浪费 × 占比因子（见 scoring-model/TRACKER.md Q-4）。
// 路径 A/B 共用，是切换非线性的唯一改动点。
// totalCost 为当前可用总法力，剩余占比越大惩罚越重；小占比浪费不会过度惩罚。
fun remainingCostPenalty(remainingCost: Int, totalCost: Int): Double {
    if (totalCost <= 0) return 0.0
    val ratio = remainingCost.toDouble() / totalCost.toDouble()
    return CostWeight * sqrt(remainingCost.toDouble()) * ratio.pow(PenaltyRatioExponent)
}


//每个人都不同 等待动作的操作 例如等待发现的动作
const val AwaitAnimationTime: Long = 1000
const val UseAnimationTime: Long = 1500
const val ChangeAnimationTime: Long = 2500
const val FourAnimationTime: Long = AwaitAnimationTime * 4






