package lin.bean

import lin.domain.use.plan.CardUseConfig

/**
 * 🌟 不可变复合配置元组：单次 HashMap 查找的强类型只读载体
 */
class CardCombinedConfig(
    val weightInfo: CardWeightInfo,
    val groupIds: Set<String> = emptySet(),
    val useConfig: CardUseConfig = CardUseConfig()
)
