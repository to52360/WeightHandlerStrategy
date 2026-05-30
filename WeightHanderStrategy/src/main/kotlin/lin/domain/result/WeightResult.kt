package lin.domain.result

import lin.bean.ComboCard
import lin.domain.context.CostWeight
import lin.domain.context.NotWeight
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
        if (isLessCost()) {//这里还是存在使用负数很离谱的情况
            this.bestCombination = _canUseCardsByHandler
            val lessCost = cost - costSum()
            extWeight -= lessCost * CostWeight
        }
        else {
            this.bestCombination = findStrategy.findBestCombination(_canUseCardsByHandler, cost)
        }

    }

    fun addUseCard(comboCard: ComboCard) {
        //myLog.info { "中途添加卡牌,卡牌为:${comboCard}" }
        if (comboCard.canUse()) this.bestCombination = this.bestCombination + comboCard
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
