package lin.rule.defined


import lin.bean.CardWeightInfo
import lin.bean.ComboCard
import lin.serviceLoader.weightRule.utils.parseRace
import lin.weightHandler.condition.bean.ConditionGroup


typealias ContextualRuleSpec = RuleBuildContext.() -> RuleLogic
typealias RuleConfigParse = (Array<Double>) -> List<CardWeightInfo>

class BuildRuleFactory(infoMap: Map<String, CardWeightInfo>) {
    private val parse by lazy { ruleConfigParse(infoMap) }
    private fun build(
        spec: ContextualRuleSpec,
        ids: ConditionGroup.() -> Array<Double>
    ): RuleBuilder {
        val factory: RuleFactory = { group ->
            val weights = parse(group.ids())
            val ctx = RuleBuildContext(group, parse, ids)
            ctx.spec()
        }

        return RuleBuilder().factory(factory)
    }
}

class RuleBuildContext(
    val conditionGroup: ConditionGroup,
    private val ruleConfigParse: RuleConfigParse,
    ids: ConditionGroup.() -> Array<Double>
) {
    val cardWeights by lazy {
        ruleConfigParse(conditionGroup.ids())
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

    fun cardWeights(ids: ConditionGroup.() -> Array<Double>) = ruleConfigParse(conditionGroup.ids())
    fun races(ids: ConditionGroup.() -> Array<Double>) = cardWeights(ids).map { it.parseRace() }
    fun hasRace(ids: ConditionGroup.() -> Array<Double>): (List<ComboCard>) -> Boolean {
        val isRace = isRace(ids)
        return { comboCards -> comboCards.any { card -> isRace(card) } }
    }

    fun isRace(ids: ConditionGroup.() -> Array<Double>): (ComboCard) -> Boolean {
        val races = races(ids)
        return { comboCard -> races.contains(comboCard.card.cardRace) }
    }

    val isGroup: (ComboCard) -> Boolean by lazy {
        { comboCard -> conditionGroup.depByWeightIds.any { it == comboCard.groupId() } }
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