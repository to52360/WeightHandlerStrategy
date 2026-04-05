package lin.rule.build

import lin.bean.CardWeightInfo
import lin.bean.ComboCard
import lin.rule.registry.RuleArgsParser
import lin.rule.registry.RuleArgsReader
import lin.rule.tree.RuleConfig
import lin.serviceLoader.weightRule.utils.parseRace

typealias ContextualRuleSpec = RuleBuildContext.() -> RuleLogic
typealias RuleConfigParse = (List<Double>) -> List<CardWeightInfo>

class BuildRuleFactory(infoMap: Map<String, CardWeightInfo>) {
    private val parse by lazy { ruleConfigParse(infoMap) }

    private fun build(
        spec: ContextualRuleSpec,
        ids: RuleConfig.() -> List<Double>
    ): RuleBuilder {
        val factory: RuleFactory = { ruleConfig ->
            val ctx = RuleBuildContext(ruleConfig, parse, ids)
            ctx.spec()
        }
        return RuleBuilder().factory(factory)
    }
}

class RuleBuildContext(
    val ruleConfig: RuleConfig,
    private val ruleConfigParse: RuleConfigParse,
    ids: RuleConfig.() -> List<Double>
) {
    private val parsedArgsCache = mutableMapOf<RuleArgsParser<*>, Any>()

    val cardWeights by lazy {
        ruleConfigParse(ruleConfig.ids())
    }

    val races by lazy {
        cardWeights.map { it.parseRace() }
    }

    val isRace: (ComboCard) -> Boolean by lazy {
        val races = this.races
        { comboCard -> races.contains(comboCard.card.cardRace) }
    }

    val hasRace: (List<ComboCard>) -> Boolean by lazy {
        val isRace = this.isRace
        { comboCards -> comboCards.any(isRace) }
    }

    fun cardWeights(ids: RuleConfig.() -> List<Double>) = ruleConfigParse(ruleConfig.ids())

    fun races(ids: RuleConfig.() -> List<Double>) = cardWeights(ids).map { it.parseRace() }

    fun hasRace(ids: RuleConfig.() -> List<Double>): (List<ComboCard>) -> Boolean {
        val isRace = isRace(ids)
        return { comboCards -> comboCards.any { card -> isRace(card) } }
    }

    fun isRace(ids: RuleConfig.() -> List<Double>): (ComboCard) -> Boolean {
        val races = races(ids)
        return { comboCard -> races.contains(comboCard.card.cardRace) }
    }

    val isGroup: (ComboCard) -> Boolean by lazy {
        { comboCard -> ruleConfig.depByWeightIds.any { it == comboCard.groupId() } }
    }

    fun <T : Any> parseArgs(parser: RuleArgsParser<T>): T {
        @Suppress("UNCHECKED_CAST")
        val cached = parsedArgsCache[parser] as? T
        if (cached != null) return cached
        val parsed = parser.parse(ruleConfig.args)
        parsedArgsCache[parser] = parsed
        return parsed
    }

    fun <T : Any> parseArgs(parser: RuleArgsReader.() -> T): T {
        return RuleArgsReader(ruleConfig.args).parser()
    }

}

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
