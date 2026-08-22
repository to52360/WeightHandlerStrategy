package lin.domain.strategy

import lin.bean.COINGroupId
import lin.bean.ComboCard
import lin.domain.MyWarManage
import lin.domain.WeightHandlerDomain
import lin.domain.context.CostWeight
import lin.domain.context.NotWeight
import lin.domain.result.*
import lin.domain.strategy.FindComboStrategy.Companion.DEF_PRIORITY
import lin.domain.strategy.FindComboStrategy.Companion.EXT_COST_PRIORITY
import lin.domain.use.tryUseCard
import lin.myLog
import lin.serviceLoader.cardInfoProvide.COINProvide
import lin.warExt.my.base.getResource


typealias FindRule = (ComboCard) -> Boolean
typealias ExtCostConfig = (Int, List<ComboCard>) -> Pair<Int, Double>
typealias FindNowResult = Pair<Map<Boolean, List<ComboCard>>, WeightResult>
/**
 * 查询组合策略
 */
interface FindComboStrategy {
    companion object {
        const val EXT_COST_PRIORITY = 5
        const val DEF_PRIORITY = 10
        const val SKILL_PRIORITY = 15
    }

    fun priority(): Int
    fun find(findPlanner: FindPlanner): CmdPlanner
    fun find(findPlanner: FindPlanner, weightResult: WeightResult): WeightResult {
        return weightResult
    }

}

// 扩展函数：只有当存在满足 predicate 的卡时，才执行 block
inline fun FindPlanner.findIfAny(
    predicate: (ComboCard) -> Boolean,
    block: FindPlanner.() -> WeightResult
): CmdPlanner {
    val canUseCards = warManage.canUseCards
    if (!canUseCards.any(predicate)) {
        return ContinuePlanner
    }
    return block().toPlanner() // 在 this = FindPlanner 上下文中执行
}


class DefFindStrategy : FindComboStrategy {
    override fun priority(): Int {
        return DEF_PRIORITY
    }

    override fun find(
        findPlanner: FindPlanner
    ): CmdPlanner {
        val weightHandlerDomain = findPlanner.weightHandlerDomain
        return ResultPlanner(weightHandlerDomain.findCombination())
    }

}

fun ComboCard.extCost(): Int {
    return this.cardWeightInfo?.cardContext?.getMetadata(COINProvide.coinKey) ?: 0
}

class ExtCostStrategy : FindComboStrategy {

    companion object {
        val extCostPredicate: FindRule = { it.useGroupId == COINGroupId }
        val extCostConfig: ExtCostConfig = { cost, extCostCards ->
            var reduceWeight = NotWeight
            val extCost = extCostCards.sumOf { it.extCost() }
            if (cost < 5) reduceWeight = -extCost * CostWeight
            Pair(extCost, reduceWeight)
        }
    }

    override fun priority(): Int {
        return EXT_COST_PRIORITY
    }


    override fun find(findPlanner: FindPlanner): CmdPlanner {
        return findPlanner.findIfAny(extCostPredicate) {
            val result = evaluateCurrentCombos(extCostPredicate).evaluateWithSkippedCards({ skipCard ->
                extCostConfig(warManage.getResource(), skipCard)
            }) { skip ->
                skip.forEach {
                    warManage.tryUseCard(it)
                }
            }
            result
        }

    }
}

class FindPlanner(val warManage: MyWarManage, val weightHandlerDomain: WeightHandlerDomain) {
    fun evaluateCurrentCombos(extCostPredicate: FindRule): FindNowResult {
        val canUseCardsByCost = warManage.canUseCards
        val canUseCardsByGroup = canUseCardsByCost.groupBy { extCostPredicate(it) }
        return evaluateCurrentCombos(canUseCardsByGroup)
    }

    fun evaluateCurrentCombos(canUseCardsByGroup: Map<Boolean, List<ComboCard>>): FindNowResult {
        val canUseCard = canUseCardsByGroup[false]
        var nowWeightResult: WeightResult = EmptyWeightResult
        canUseCard?.let {
            nowWeightResult = weightHandlerDomain.findCombination(canUseCardsByCost = it)
        }
        return Pair(canUseCardsByGroup, nowWeightResult)
    }

    /**
     * 两个lambda的集合都是过滤条件一致
     */
    inline fun FindNowResult.evaluateWithSkippedCards(
        calWeightAndExtCost: (List<ComboCard>) -> Pair<Int, Double>,
        consumer: (List<ComboCard>) -> Unit
    ): WeightResult {
        val canUseCardsByGroup = this.first
        val nowWeightResult = this.second
        val skipCard = canUseCardsByGroup[true]
        skipCard?.let { skipCard ->
            val (extCost, extWeight) = calWeightAndExtCost(skipCard)
            val extWeightResult = evaluateWithExtra(extCost, skipCard)
            if (extWeightResult is EndWeightResult) {
                extWeightResult.extWeight = extWeight
            }
            return compareSumWeight(nowWeightResult, extWeightResult) { consumer(skipCard) }
        }
        myLog.warn { "没有比较数据,可能分组函数有问题,返回原始组合" }
        return nowWeightResult
    }

    fun evaluateWithExtra(extCost: Int, skipCard: List<ComboCard>): WeightResult {
        warManage.consumeExtCost(extCost) { sumExtCost ->
            val comboCards = warManage.canUseCardsByCost(sumExtCost).copy(skipCard)
            val extCostWeightResult = weightHandlerDomain.findCombination(sumExtCost, comboCards)
            return extCostWeightResult
        }
        // 不可能执行到这里
        throw IllegalStateException("Unreachable code")
    }


    fun copyResult(endWeightResult: EndWeightResult, extCost: Int, findRule: FindRule): WeightResult {
        warManage.consumeExtCost(extCost) { sumExtCost ->
            val noHasFindRule = endWeightResult.canUseCards.filter { it.cost() <= sumExtCost && !findRule(it) }
            if (noHasFindRule.isEmpty()) return EmptyWeightResult
            val newWeight = EndWeightResult(noHasFindRule, sumExtCost)
            newWeight.addAll(noHasFindRule)
            newWeight.findBestCombination(warManage.isFull)
            return newWeight
        }
        throw RuntimeException("额外费用事务失败")

    }

    fun List<ComboCard>.copy(skipComboCards: List<ComboCard>): List<ComboCard> {
        return this.filter { it !in skipComboCards }.map { warManage.parseComboCard(it.card) }
    }

    inline fun compareSumWeight(
        weightResult: WeightResult,
        extWeightResult: WeightResult,
        runnable: () -> Unit
    ): WeightResult {
        val nowWeight = weightResult.weightSum()
        weightResult.log()
        val extCostWeight = extWeightResult.weightSum()
        extWeightResult.log()
        if (nowWeight >= extCostWeight) {
            return weightResult
        } else {
            runnable()
            return extWeightResult
        }
    }
}








