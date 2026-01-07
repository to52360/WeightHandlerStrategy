package lin.rule.defined

import club.xiaojiawei.hsscriptcardsdk.enums.CardRaceEnum
import lin.bean.CardWeightInfo
import lin.serviceLoader.weightRule.utils.parseRace
import lin.weightHandler.condition.bean.ConditionGroup


class RuleConfigMapping(infoMap: Map<String, CardWeightInfo>) {
    private val ruleConfigParse by lazy {
        ruleConfigParse(infoMap)
    }
    val depIdMapping by lazy {
        ruleConfigParse.findByGroupId<ConditionGroup> { conditionGroup ->
            conditionGroup.depByWeightIds
        }
    }
    val bidIdMapping by lazy {
        ruleConfigParse.findByGroupId<ConditionGroup> { conditionGroup ->
            conditionGroup.bindId
        }
    }
    val depIdMappingRace by lazy {
        infoMapRace(depIdMapping)
    }

}

data class RuleCgMapping<T : Any>(val conditionGroup: ConditionGroup, val t: T)

typealias RuleConfigParse = (Array<Double>) -> List<CardWeightInfo>

fun ruleConfigParse(infoMap: Map<String, CardWeightInfo>): RuleConfigParse {
    val infoByGroupId = infoMap.values.groupBy { it.groupId }
    return { groupIds ->
        val depWeightInfos = mutableListOf<CardWeightInfo>()
        groupIds.forEach { depId ->
            infoByGroupId[depId]?.run {
                depWeightInfos.addAll(this)
            }
        }
        depWeightInfos
    }
}

fun <T : Any> infoMapRace(mappingInfos: (T) -> List<CardWeightInfo>): (T) -> List<CardRaceEnum> {
    return { any -> mappingInfos(any).map { it.parseRace() } }
}

inline fun <reified T : Any> RuleConfigParse.findByGroupId(crossinline mapGroupIds: (T) -> Array<Double>): (T) -> List<CardWeightInfo> {
    return { any ->
        val groupIds = mapGroupIds(any)
        this(groupIds)
    }
}