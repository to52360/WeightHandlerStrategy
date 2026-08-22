package lin.serviceLoader.weightRule.utils

import lin.bean.CardWeightInfo


class PredicateListByRace : DepWeightInfoDelegate<DepToPredicateList> {

    override lateinit var depInfoToPredicate: DepToPredicateList
    override fun initByWeightInfo(cardWeightInfoList: List<CardWeightInfo>) {
        depInfoToPredicate = cardWeightInfoList.infoGetRaceToPredicates()
    }
}

class PredicateOneByRace : DepWeightInfoDelegate<DepToPredicate> {

    override lateinit var depInfoToPredicate: DepToPredicate
    override fun initByWeightInfo(cardWeightInfoList: List<CardWeightInfo>) {
        depInfoToPredicate = cardWeightInfoList.infoGetRaceToPredicateByOne()
    }
}
