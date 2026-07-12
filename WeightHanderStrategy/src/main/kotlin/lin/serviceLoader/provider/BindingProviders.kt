package lin.serviceLoader.provider

import lin.bean.usePlan.GroupUseOverride
import lin.config.find.def.BindInfo
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
