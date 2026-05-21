package lin.config


import lin.bean.CardWeightInfo
import lin.bean.MetadataKey
import lin.domain.use.UseStrategy

import lin.rule.tree.EvaluatorInstanceNode
import lin.serviceLoader.weightRule.WeightRule

sealed interface BaseConfig : CardConfig
sealed interface CardAttributeConfig : BaseConfig

data class UseConfig(
    val useGroupId: Int? = null,
    val useGroupOrder: Double? = null,
    val useStrategyList: List<UseStrategy> = emptyList()
) : CardAttributeConfig

// 卡牌类型配置接口
interface CardType : CardAttributeConfig

sealed interface Rule : BaseConfig

data class Rules(val rules: List<WeightRule>) : Rule {
    constructor(rule: WeightRule) : this(listOf(rule))
}
data class EvaluatorTreeRoot(val root: EvaluatorInstanceNode) : Rule

/**
 *  直接修改通用的,用于基础数值类型/临时过度,不分组管理的
 */
fun interface CardWeightConfigurer : CardAttributeConfig {
    operator fun invoke(cardWeightInfo: CardWeightInfo)
}

data class CardWeightContext<T : Any>(val key: MetadataKey<T>, val value: T) : CardAttributeConfig