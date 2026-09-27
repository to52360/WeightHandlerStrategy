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
    // 运行时判定的组级使用动作：groupId → 策略列表（T-002，谓词组专用）。
    // 谓词组成员在运行时才确定，expandSlices 无法展开成 cardId，故保留「组级作用域」，
    // 由 ComboCard 构造时用 hasGroup(groupId) 判定后与 useStrategies 合并。
    // 所有卡共享同一个不可变 Map 实例（全局一份，非每卡拷贝）。
    val groupStrategies: Map<String, List<UseStrategy>> = emptyMap(),
    // 用途标签：唯一来源是 PurposeStep（CardPurposeProvider 用户配置 + 机制牌硬编码注入合并，T-003）。启动期透传，运行期只读。
    val purposeTags: Set<PurposeTagId> = emptySet(),
    // 卡级「使用后重新规划」声明（CardPurpose.replanAfterUse 的静态快照，T-013）。
    // 与 purposeTags 同为运行时重算 useIntent 的输入——组级 override 未声明时回落此值，
    // 故必须留存，否则谓词组命中重算时会丢失卡级兜底。
    val purposeReplanAfterUse: Boolean = false,
    // 条件化阶段覆盖：唯一来源是 GroupBehaviorStep（OverrideBehavior.conditionalStage 透传）。启动期透传，运行期只读。
    val conditionalStage: ConditionalStageOverride? = null,
    // 分组级余费门槛 N（D-007，T-019）：唯一来源是 GroupBehaviorStep（SurplusGateBehavior 透传）。
    // 覆盖链（[ComboCard.idleThreshold] / [lin.bean.resolveIdleThreshold]）的第二层兜底：逐卡小数位
    // （weightInfo.surplusIdleThreshold）> 本分组行为 > 标签预设 > 默认 0（D-012）。
    val groupSurplusIdleThreshold: Int? = null,
)
