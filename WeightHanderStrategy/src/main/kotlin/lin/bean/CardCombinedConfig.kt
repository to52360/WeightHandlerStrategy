package lin.bean

import lin.bean.usePlan.CardComboEntry
import lin.bean.usePlan.CardComboUseBinding
import lin.bean.usePlan.UseIntent
import lin.domain.use.UseStrategy


/**
 * 🌟 不可变复合配置元组：单次 HashMap 查找的强类型只读载体
 */
class CardCombinedConfig(
    val weightInfo: CardWeightInfo,
    val groupIds: Set<String> = emptySet(),
    val useIntent: UseIntent = UseIntent(),
    val comboEntries: List<CardComboEntry> = emptyList(),
    val comboUseBindings: List<CardComboUseBinding> = emptyList(),
    // 配置侧声明的使用动作（A 类：经 GroupBehaviorStep 从 DB 行为表加载）。
    // before/after 是同一关注点的执行时机子类型，配置元组层不平铺，由消费端（ComboCard/UseDomain）按类型分流。
    val useStrategies: List<UseStrategy> = emptyList(),
)
