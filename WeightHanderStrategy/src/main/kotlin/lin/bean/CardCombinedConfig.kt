package lin.bean

import lin.bean.usePlan.CardComboEntry
import lin.bean.usePlan.CardComboUseBinding
import lin.bean.usePlan.CardUseConfig


/**
 * 🌟 不可变复合配置元组：单次 HashMap 查找的强类型只读载体
 */
class CardCombinedConfig(
    val weightInfo: CardWeightInfo,
    val groupIds: Set<String> = emptySet(),
    val useConfig: CardUseConfig = CardUseConfig(),
    val comboEntries: List<CardComboEntry> = emptyList(),
    val comboUseBindings: List<CardComboUseBinding> = emptyList()
)
