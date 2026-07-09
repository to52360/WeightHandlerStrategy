package lin.domain.context

import kotlin.math.sqrt

//表示一费5点权重,可以在打不满费用时打出
const val CostWeight = 5.0
const val MaxCostWeight = 10.0
const val NotWeight = 0.0
const val BaseWeight = 1.0
const val OrderWeight = 1.0
const val UnUseWeight = -100.0
const val UseSkillWeight = -7.0

// 基础分原语：由静态费用派生，凹函数（边际费用价值递减，见 scoring-model/TRACKER.md Q-2）。
// 1费→5, 5费→11.2, 10费→15.8。所有卡恒有基础分，与额外分(配置溢价)/规则分正交相加。
fun baseScore(cost: Int): Double = CostWeight * sqrt(cost.toDouble())

// 剩余法力惩罚原语：凹函数（边际费用价值递减，见 scoring-model/TRACKER.md Q-4），
// 与 baseScore 同一套变换保证一致。路径 A/B 共用，是切换非线性的唯一改动点。
fun remainingCostPenalty(remainingCost: Int): Double = CostWeight * sqrt(remainingCost.toDouble())


//每个人都不同 等待动作的操作 例如等待发现的动作
const val AwaitAnimationTime: Long = 1000
const val UseAnimationTime: Long = 1500
const val ChangeAnimationTime: Long = 2500
const val FourAnimationTime: Long = AwaitAnimationTime * 4






