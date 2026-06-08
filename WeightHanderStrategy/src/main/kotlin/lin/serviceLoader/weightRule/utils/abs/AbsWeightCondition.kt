package lin.serviceLoader.weightRule.utils.abs

import lin.bean.CardWeightInfo
import lin.domain.context.CostWeight
import lin.domain.context.NotWeight
import lin.serviceLoader.weightRule.DepByWeightGroupId
import lin.serviceLoader.weightRule.DepWeightInfo
import lin.serviceLoader.weightRule.WeightCondition
import lin.serviceLoader.weightRule.utils.DepToPredicate
import lin.serviceLoader.weightRule.utils.DepToPredicateList
import lin.serviceLoader.weightRule.utils.infoGetRaceToPredicateByOne
import lin.serviceLoader.weightRule.utils.infoGetRaceToPredicates

/**
 * 抽象类,
 */


abstract class AbsWeightConditionDepToPredicate<Predicate : Any>(override var groupWeight: Double = CostWeight) :
    AbsWeightCondition() {
    lateinit var predicateFromDep: Predicate
}
abstract class AbsWeightCondition(override var groupWeight: Double = CostWeight) : WeightCondition {
    protected var unConditionWeight: Double = NotWeight
    protected var number: Int = 0
    override fun setUnCondWeight(unConditionWeight: Double) {
        this.unConditionWeight = unConditionWeight
    }
    override fun setNum(num: Int) {
        this.number = num
    }
}

abstract class PredicateListByGroup : AbsWeightConditionDepToPredicate<DepToPredicateList>(), DepByWeightGroupId {
    override fun initByGroupIds(groupIds: Array<Double>) {
        predicateFromDep = { canUseCard ->
            groupIds.any { groupId -> canUseCard.any { groupId == it.groupId() } }
        }
    }
}

abstract class PredicateByGroup : AbsWeightConditionDepToPredicate<DepToPredicate>(), DepByWeightGroupId {
    override fun initByGroupIds(groupIds: Array<Double>) {
        predicateFromDep = { canUseCard ->
            groupIds.any { canUseCard.groupId() == it }
        }
    }
}

abstract class PredicateListByRace : AbsWeightConditionDepToPredicate<DepToPredicateList>(), DepWeightInfo {
    override fun initByWeightInfo(cardWeightInfoList: List<CardWeightInfo>) {
        predicateFromDep = cardWeightInfoList.infoGetRaceToPredicates()
    }
}

abstract class PredicateByRace : AbsWeightConditionDepToPredicate<DepToPredicate>(), DepWeightInfo {
    override fun initByWeightInfo(cardWeightInfoList: List<CardWeightInfo>) {
        predicateFromDep = cardWeightInfoList.infoGetRaceToPredicateByOne()
    }
}
