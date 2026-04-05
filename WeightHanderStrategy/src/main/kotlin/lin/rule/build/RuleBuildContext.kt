package lin.rule.build


import lin.bean.CardWeightInfo
import lin.bean.ComboCard
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

    // --- 动态属性快捷获取 ---
    fun dynamicInt(name: String, default: Int = 0): Int {
        return (ruleConfig.args[name] as? Number)?.toInt()
            ?: ruleConfig.args[name]?.toString()?.toIntOrNull()
            ?: default
    }

    fun dynamicBoolean(name: String, default: Boolean = false): Boolean {
        val value = ruleConfig.args[name]
        if (value is Boolean) return value
        return value?.toString()?.toBooleanStrictOrNull() ?: default
    }

    fun dynamicString(name: String, default: String = ""): String {
        return ruleConfig.args[name]?.toString() ?: default
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
