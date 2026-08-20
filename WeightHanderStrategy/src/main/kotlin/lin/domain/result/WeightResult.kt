package lin.domain.result

import lin.bean.ComboCard
import lin.bean.passesFirstRoundCandidate
import lin.domain.context.NotWeight
import lin.domain.context.comboPenalty
import lin.domain.context.remainingCostPenalty
import lin.myLog

sealed class CmdPlanner
object ContinuePlanner : CmdPlanner()
class ResultPlanner(val weightResult: WeightResult) : CmdPlanner()

sealed class WeightResult {
    open fun weightSum(): Double {
        return NotWeight
    }

    open fun log() {
        myLog.info { "Empty" }
    }
    open fun toPlanner(): ResultPlanner {
        return ResultPlanner(this)
    }
}

object EmptyWeightResult : WeightResult() {
    val emptyWeightResult = ResultPlanner(this)
    override fun toPlanner(): ResultPlanner {
        return emptyWeightResult
    }

}

class EndWeightResult(
    val canUseCards: List<ComboCard>,
    val cost: Int,
    val findStrategy: FindBestCombination = DefaultFindBestCombination
) : WeightResult() {
    //todo-future 存在直接操作权重,导致查找不到元素 想改成ArrayList,太复杂了,后面再说
    private val _canUseCardsByHandler = mutableListOf<ComboCard>()

    //todo 不使用是不是断开,要核实一下
    val unUseCards: List<ComboCard>
        get() = _unUseCards
    private val _unUseCards: MutableList<ComboCard> by lazy {
        mutableListOf()
    }


    var bestCombination: List<ComboCard> = emptyList()
        private set
    var extWeight = 0.0

    /**
     * 处理权重之后的挫折
     */
    fun processWeightAfter(comboCard: ComboCard) {
        if (comboCard.isUnUse()) {
            _unUseCards.add(comboCard)

        } else _canUseCardsByHandler.add(comboCard)
    }
    fun addAll(comboCards: List<ComboCard>) {
        _canUseCardsByHandler.addAll(comboCards)
    }

    /**
     * 判断是否可以直接使用
     */
    fun isLessCost(): Boolean {
        val result = _canUseCardsByHandler.size == 1 || _canUseCardsByHandler.sumOf { it.cost() } < cost
        return result
    }

    override fun weightSum() = bestCombination.sumOf { it.powerWeight } + extWeight
    fun costSum() = bestCombination.sumOf { it.cost() }
    fun notAbleUseCards(): Boolean = _canUseCardsByHandler.isEmpty()

    override fun log() {
        myLog.info { "costSum: ${costSum()},weightSum: ${weightSum()},成员:${bestCombination}" }
    }

    fun lessAbleUseCards(): List<ComboCard> {
        if (isLessCost()) return emptyList()
        val lessAbleUseCards = _canUseCardsByHandler - bestCombination
        return lessAbleUseCards
    }

    fun findBestCombination() {
        // T-008：第一轮候选过滤——NORMAL 全纳、TACTICS_DOMINANT 需战术命中、SURPLUS_ONLY 排除。
        // 与 EvalOutcome.Banned 硬禁区分：只进不出候选，不改 unUse，卡保持可用。
        val firstRoundCandidates = _canUseCardsByHandler.filter { it.passesFirstRoundCandidate() }
        if (isLessCost()) {// 预评估快路：无替代组合，直接全收
            this.bestCombination = firstRoundCandidates
            // S-0.2: 原 `extWeight -= lessCost * CostWeight` 在 lessCost 大时产生离谱负数并污染
            // extWeight 通道。改为惩罚上限钳制为不超过本组合自身权重和，避免负分失控；
            // 最终量纲/是否保留由 Q-3 模型决策定。
            val lessCost = (cost - costSum()).coerceAtLeast(0)
            if (bestCombination.isNotEmpty()) {
                val baseWeightSum = bestCombination.sumOf { it.powerWeight }
                val totalPenalty = remainingCostPenalty(lessCost, cost) + comboPenalty(bestCombination.size)
                val penalty = totalPenalty.coerceAtMost(baseWeightSum)
                extWeight -= penalty
            }
        }
        else {
            this.bestCombination = findStrategy.findBestCombination(firstRoundCandidates, cost)
        }

    }

    fun addUseCard(comboCard: ComboCard) {
        //myLog.info { "中途添加卡牌,卡牌为:${comboCard}" }
        if (comboCard.canUse()) this.bestCombination += comboCard
        else _canUseCardsByHandler.add(comboCard)
    }

}

inline fun WeightResult.compareSumWeight(
    compareWeight: WeightResult,
    extWeight: Double,
    action: (WeightResult) -> Unit
): WeightResult {
    if (this.weightSum() + extWeight >= compareWeight.weightSum()) {
        action(this)
        return this
    } else {
        return compareWeight
    }

}
