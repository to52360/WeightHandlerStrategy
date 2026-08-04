package lin.bean

import lin.bean.usePlan.*
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
    // 配置侧声明的使用动作：唯一来源是 ConfigBindingStep 管线（GroupBehaviorStep 从 group_behavior 表读 USE_ACTION 行为装配）。
    // before/after 是同一关注点的执行时机子类型，配置元组层不平铺，由消费端（ComboCard/UseDomain）按类型分流。
    // 启动装配期由 GroupBehaviorStep 一次性写入，运行期只读，故用不可变 List。
    val useStrategies: List<UseStrategy> = emptyList(),
    // 用途标签：唯一来源是 PurposeStep（从 CardPurposeProvider 加载）。启动期透传，运行期只读。
    val purposeTags: Set<PurposeTagId> = emptySet(),
    // 条件化阶段覆盖：唯一来源是 GroupBehaviorStep（OverrideBehavior.conditionalStage 透传）。启动期透传，运行期只读。
    val conditionalStage: ConditionalStageOverride? = null,
)
