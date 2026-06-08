package lin.serviceLoader.weightRule.utils

/**
 * 委托存在指定组判断函数
 * 存在指定分组卡牌
 */
class PredicateGroupIds : DepWeightGroupDelegate<DepToPredicateList> {
    override lateinit var depToPredicate: DepToPredicateList


    override fun initByGroupIds(groupIds: Array<Double>) {
        depToPredicate = { canUseCard ->
            groupIds.any { groupId -> canUseCard.any { groupId == it.groupId() } }
        }
    }
}

class PredicateGroup : DepWeightGroupDelegate<DepToPredicate> {
    override lateinit var depToPredicate: DepToPredicate


    override fun initByGroupIds(groupIds: Array<Double>) {
        depToPredicate = { canUseCard ->
            groupIds.any { canUseCard.groupId() == it }
        }
    }
}
