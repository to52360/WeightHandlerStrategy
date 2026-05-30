package lin.bean

import lin.bean.usePlan.CardComboBinding
import lin.bean.usePlan.CardComboUseBinding
import lin.bean.usePlan.CardUseConfig
import lin.bean.usePlan.UseIntent


/**
 * 🌟 不可变复合配置元组：单次 HashMap 查找的强类型只读载体
 */
class CardCombinedConfig(
    val weightInfo: CardWeightInfo,
    val groupIds: Set<String> = emptySet(),
    val useConfig: CardUseConfig = CardUseConfig(),
    val useIntent: UseIntent? = null,
    val comboBindings: List<CardComboBinding> = emptyList(),
    val comboUseBindings: List<CardComboUseBinding> = emptyList()
)
