package lin.config


import lin.bean.CardWeightInfo
import lin.bean.MetadataKey
import lin.domain.use.UseStrategy
import lin.rule.handler.RuleLevel
import lin.serviceLoader.weightRule.IntentRule
import lin.serviceLoader.weightRule.WeightRule

sealed interface BaseConfig : CardConfig

data class UseConfig(
    val useGroupId: Int? = null,
    val useGroupOrder: Double? = null,
    val useStrategyList: List<UseStrategy> = emptyList()
) : BaseConfig

// 卡牌类型配置接口
interface CardType : BaseConfig

sealed interface Rule : BaseConfig
data class RuleMap(val ruleMap: Map<RuleLevel, List<IntentRule>>) : Rule
data class Rules(val rules: List<WeightRule>) : Rule {
    constructor(rule: WeightRule) : this(listOf(rule))
}

/**
 *  直接修改通用的,用于基础数值类型/临时过度,不分组管理的
 */
fun interface CardWeightConfigurer : BaseConfig {
    operator fun invoke(cardWeightInfo: CardWeightInfo)
}

data class CardWeightContext<T : Any>(val key: MetadataKey<T>, val value: T) : BaseConfig