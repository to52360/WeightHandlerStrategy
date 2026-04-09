package lin.rule.build

import lin.bean.CardWeightInfo
import lin.bean.ComboCard
import lin.rule.registry.mapToRuleArgs
import lin.rule.tree.RuleConfig
import lin.serviceLoader.weightRule.utils.parseRace
import kotlin.reflect.KClass

typealias ContextualRuleSpec<T> = RuleBuildContext<T>.() -> RuleLogic
typealias RuleConfigParse = (List<Double>) -> List<CardWeightInfo>

class BuildRuleFactory(infoMap: Map<String, CardWeightInfo>) {
    private val parse by lazy { ruleConfigParse(infoMap) }

    fun <T : Any> build(
        spec: ContextualRuleSpec<T>,
        parameterType: KClass<T>,
        ids: RuleConfig.() -> List<Double>
    ): RuleBuilder<T> {
        val factory: RuleFactory<T> = { ruleConfig, params ->
            val ctx = RuleBuildContext(ruleConfig, params, parse, ids)
            ctx.spec()
        }
        return RuleBuilder(parameterType).factory(factory)
    }
}

class RuleBuildContext<T : Any>(
    val ruleConfig: RuleConfig,
    val params: T,
    private val ruleConfigParse: RuleConfigParse,
    ids: RuleConfig.() -> List<Double>
) {
    private val parsedArgsCache = mutableMapOf<KClass<*>, Any>()

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

    fun <U : Any> parseArgs(parameterType: KClass<U>): U {
        @Suppress("UNCHECKED_CAST")
        val cached = parsedArgsCache[parameterType] as? U
        if (cached != null) return cached
        val parsed = mapToRuleArgs(ruleConfig.args, parameterType)
        parsedArgsCache[parameterType] = parsed
        return parsed
    }

    inline fun <reified U : Any> parseArgs(): U = parseArgs(U::class)
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
