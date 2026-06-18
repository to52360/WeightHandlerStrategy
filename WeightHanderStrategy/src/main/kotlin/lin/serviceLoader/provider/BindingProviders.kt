package lin.serviceLoader.provider

import lin.bean.usePlan.GroupUseOverride
import lin.config.find.def.BindInfo

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

    /**
     * 提供分组级行为覆盖（从 Binding 的行为属性读取）。
     * 默认返回空 Map，由 configUi 侧覆盖实现。
     */
    fun provideBindingOverrides(): Map<String, GroupUseOverride> = emptyMap()
}
