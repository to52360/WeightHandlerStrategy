package lin.rule.defined

import club.xiaojiawei.hsscriptcardsdk.enums.CardRaceEnum
import lin.bean.CardWeightInfo
import lin.serviceLoader.weightRule.utils.parseRace
import lin.weightHandler.condition.bean.ConditionGroup

// 定义提取器的别名，现在它只关心 ConditionGroup
typealias CGExtractor<T> = (ConditionGroup) -> T

class RuleBuildByWeightInfo(infoMap: Map<String, CardWeightInfo>) {
    private val ruleConfigParse by lazy {
        ruleConfigParse(infoMap)
    }

    /**
     * 核心算子：将一个简单的“坐标提取”逻辑，转化为一个完整的“数据提取”过程
     * 即：(CG -> Array<Double>) -> (CG -> List<CardWeightInfo>)
     */
    fun weightInfos(
        coordinate: (ConditionGroup) -> Array<Double>
    ): CGExtractor<List<CardWeightInfo>> = { cg ->
        ruleConfigParse(coordinate(cg))
    }

    /**
     * 派生算子：在重量信息的基础上进一步提取种族
     */
    fun raceInfos(
        coordinate: (ConditionGroup) -> Array<Double>
    ): CGExtractor<List<CardRaceEnum>> = { cg ->
        weightInfos(coordinate)(cg).map { it.parseRace() }
    }
}