package lin.rule.build

import club.xiaojiawei.hsscriptcardsdk.enums.CardRaceEnum
import lin.bean.CardWeightInfo
import lin.bean.ComboCard
import lin.serviceLoader.weightRule.utils.parseRace

/**
 * 域外挂扩展集：将具体业务维度的辅助判断方法，从 RuleBuildContext 核心载体中剥离。
 * 遵循 SICP 的闭包构建原理：这些方法返回的应是一个高速执行的纯函数 `(...) -> Boolean`
 * 提供在规则外层构建期调用，以此来锁定缓存和闭包状态。
 */

// ---------------- 提取数据部分 ----------------

/**
 * 具有特征 HasTargetIds 的规则，独享免传参的默认提取待遇
 */
fun <T : HasTargetIds> RuleBuildContext<T>.defaultCardWeights(): List<CardWeightInfo> {
    return ruleConfigParse(params.targetIds)
}

/**
 * 任何规则都可以显式穿管 id 获取数据
 */
fun <T : Any> RuleBuildContext<T>.cardWeights(ids: List<Double>): List<CardWeightInfo> {
    return ruleConfigParse(ids)
}

fun <T : HasTargetIds> RuleBuildContext<T>.defaultRaces(): Set<CardRaceEnum> {
    return defaultCardWeights().map { it.parseRace() }.toSet()
}

fun <T : Any> RuleBuildContext<T>.races(ids: List<Double>): Set<CardRaceEnum> {
    return cardWeights(ids).map { it.parseRace() }.toSet()
}


// ---------------- 构建判定闭包部分 ----------------

/**
 * 种族判别：(显式提供 IDs 重载版) 任何配置类均可调用
 */
fun <T : Any> RuleBuildContext<T>.isRaceFunc(ids: List<Double>): (ComboCard) -> Boolean {
    val targetRaces = races(ids)
    return { comboCard -> targetRaces.contains(comboCard.card.cardRace) }
}

/**
 * 种族判别：(特征免参重载版) 仅限于 T : HasTargetIds 的上下文才有权调用！
 */
fun <T : HasTargetIds> RuleBuildContext<T>.isRaceFunc(): (ComboCard) -> Boolean {
    val targetRaces = defaultRaces()
    return { comboCard -> targetRaces.contains(comboCard.card.cardRace) }
}

fun <T : Any> RuleBuildContext<T>.hasRaceFunc(ids: List<Double>): (List<ComboCard>) -> Boolean {
    val isRaceCheck = isRaceFunc(ids)
    return { comboCards -> comboCards.any(isRaceCheck) }
}

fun <T : HasTargetIds> RuleBuildContext<T>.hasRaceFunc(): (List<ComboCard>) -> Boolean {
    val isRaceCheck = isRaceFunc()
    return { comboCards -> comboCards.any(isRaceCheck) }
}


/**
 * 保留占位思考扩展：分组判定功能
 * 由于不在上下文中心类了，你可以随时在其他任意文件中开启针对 ComboCard 的特殊判断注入
 */
// fun <T : Any> RuleBuildContext<T>.isGroupFunc(): (ComboCard) -> Boolean {
//     val weightIds = ruleConfig.depByWeightIds // 假设存在此字段
//     return { comboCard -> weightIds.contains(comboCard.groupId()) }
// }
