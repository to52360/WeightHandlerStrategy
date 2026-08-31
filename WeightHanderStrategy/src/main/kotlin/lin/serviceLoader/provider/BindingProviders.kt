package lin.serviceLoader.provider

import lin.config.find.def.BindInfo
import lin.domain.use.plan.PredicateGroupDef
import lin.rule.tree.CardGroupBinding

/**
 * 用于声明需要修改card的信息
 */
interface BindInfoProvider {
    fun provide(): List<BindInfo>
}

/**
 * 提供绑定组 ID 到 cardId 列表的反向索引。
 * key: CardGroupBinding.id
 * value: 该绑定组下的 cardId 列表
 */
interface BindingCardIdProvider {
    fun provide(): Map<String, List<String>>
}

/**
 * 提供运行时卡牌分组索引。
 * key: cardId
 * value: 该卡所属的分组 id 集合
 */
interface CardGroupIndexProvider {
    fun provide(): Map<String, Set<String>>
}

/**
 * 提供配置侧「分组行为」声明：返回启用 Manager 下的全部 [CardGroupBinding]（含 behaviors 列表）。
 * 引擎侧按需从 binding.behaviors 中提取 OVERRIDE（出牌覆盖）或 USE_ACTION（使用动作）。
 * 实现需读取 configUi DB，由 configUi 侧注册为 Koin 单例。
 */
interface GroupBehaviorProvider {
    fun provide(): List<CardGroupBinding> = emptyList()
}

/**
 * 提供「谓词组」（[lin.rule.tree.GroupMembership.Predicate]，条件定义成员的分组）的运行时定义。
 *
 * **覆盖链由配置侧解析完毕**：`组级 includeDerived > 卡组级 defaultIncludeDerived > 内建兜底 false`
 * 在读库时算好，引擎侧只见最终布尔值、不感知层级——配置语义归配置侧，引擎侧只消费。
 *
 * 默认空实现：未注册该 Provider 时等价于「没有谓词组」，全部走静态组路径。
 */
interface PredicateGroupProvider {
    fun provide(): List<PredicateGroupDef> = emptyList()
}
