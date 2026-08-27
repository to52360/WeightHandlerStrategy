package lin.config


import lin.bean.CardWeightInfo
import lin.bean.MetadataKey
import lin.domain.use.UseStrategy

import lin.rule.tree.EvaluatorInstanceNode
import lin.serviceLoader.weightRule.WeightRule

sealed interface BaseConfig : CardConfig
sealed interface CardAttributeConfig : BaseConfig

data class UseConfig(
    // T-002：旧排序通道弃用——排序已全切 UseStage/UsePlanOrderer；
    // 字段仅保留供硬币识别（useGroupId==COINGroupId）与旧 DB 兼容，勿新增消费方。
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

/** 评估树根节点。绑定任务实例化后随根注入 CardWeightInfo。 */
data class EvaluatorTreeRoot(
    val root: EvaluatorInstanceNode
) : Rule

/**
 *  直接修改通用的,用于基础数值类型/临时过度,不分组管理的
 */
fun interface CardWeightConfigurer : CardAttributeConfig {
    operator fun invoke(cardWeightInfo: CardWeightInfo)
}

data class CardWeightContext<T : Any>(val key: MetadataKey<T>, val value: T) : CardAttributeConfig