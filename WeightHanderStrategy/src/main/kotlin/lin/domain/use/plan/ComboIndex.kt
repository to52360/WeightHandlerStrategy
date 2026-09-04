package lin.domain.use.plan

import lin.bean.usePlan.*

/**
 * combo 定义的**组级索引** + 两组「组集合 → 条目」的纯映射。
 *
 * ## 为什么抽出来（T-012）
 * 「这张卡属于哪些组」在 T-002 之后有两个来源：启动期静态 `groupMap` ∪ 运行时谓词组命中
 * （见 [lin.bean.ComboCard.allGroupIds]）。combo 条目必须由**完整组集合**推导，否则谓词组
 * 引用的 combo 不生效（这正是 T-012 要补的断链）。
 *
 * 若启动期与运行时各写一遍映射逻辑，两份实现必然漂移——故统一到本类：
 * - 启动期：[ComboAssembler] 用静态 groupMap 调 [entries]/[bindings]，产出静态预算；
 * - 运行时：[ComboRuntime] 用 `allGroupIds`（含谓词组）调同样的两个函数。
 *
 * **本类不做条件求值**，只消费「已算好的组 id 集合」——谓词判定与降级语义全部在
 * [GroupMembershipRuntime] 单点收口（求值失败 = 该组不出现在集合里），本类无失败路径。
 *
 * 构造即索引，之后不可变；两个映射函数无状态、可重复调用。
 */
class ComboIndex(comboDefinitions: List<ComboPlanDefinition>) {

    private val defsByGroupId = comboDefinitions.indexComboDefByGroupId()
    private val useBindingsByGroupId = comboDefinitions.toComboUseBindingByGroupId()

    /**
     * 组集合 → 该卡涉及的 combo 条目（按 comboId 归并）。
     *
     * @param groupIds **完整**组集合（静态 ∪ 谓词）；coreMutex 的「本组过滤」也基于它，
     *   故谓词组命中后互斥判定自动生效。
     */
    fun entries(groupIds: Set<String>): List<CardComboEntry> {
        if (groupIds.isEmpty()) return emptyList()
        return groupIds
            .flatMap { defsByGroupId[it] ?: emptyList() }
            .distinctBy { it.id }
            .map { def ->
                CardComboEntry(
                    comboId = def.id,
                    score = def.score,
                    changeScore = def.changeScore,
                    coreMutexOwnGroupIds = if (def.coreMutex)
                        def.coreGroupIds.filter { it in groupIds }
                    else emptyList(),
                    counterpartGroupIds = def.depGroupIds + def.coreGroupIds,
                    coreGroupIds = def.coreGroupIds
                )
            }
    }

    /** 组集合 → 该卡涉及的出牌顺序绑定（去重口径与启动期一致）。 */
    fun bindings(groupIds: Set<String>): List<CardComboUseBinding> {
        if (groupIds.isEmpty()) return emptyList()
        return groupIds
            .flatMap { useBindingsByGroupId[it] ?: emptyList() }
            .distinctBy { "${it.comboId}:${it.beforeGroupIds}:${it.afterGroupIds}" }
    }
}
