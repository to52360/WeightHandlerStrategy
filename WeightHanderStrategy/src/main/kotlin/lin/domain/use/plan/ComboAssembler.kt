package lin.domain.use.plan

import lin.bean.usePlan.*

/**
 * Combo 编排组装器：持有组合依赖并自索引，自解析 cardId → entries / bindings。
 *
 * 作为栈上实例创建，Task 结束后随帧回收。
 * 消费方：CardConfigBindingTask（启动期组装）。
 * 下游消费者：FindBestCombination（entries）、ComboUseConstraintBuilder（bindings）。
 */
class ComboAssembler(
    private val groupMap: Map<String, Set<String>>,
    comboDefinitions: List<ComboPlanDefinition>
) {
    private val comboDefByGroupId = comboDefinitions.indexComboDefByGroupId()
    private val comboUseBindingByGroupId = comboDefinitions.toComboUseBindingByGroupId()

    /**
     * 含 coreMutex 本组过滤 + counterpart 取并集。
     */
    fun entries(cardId: String): List<CardComboEntry> {
        val groupsSet = groupMap[cardId] ?: emptySet()
        return groupsSet
            .flatMap { comboDefByGroupId[it] ?: emptyList() }
            .distinctBy { it.id }
            .map { def ->
                CardComboEntry(
                    comboId = def.id,
                    score = def.score,
                    coreMutexOwnGroupIds = if (def.coreMutex)
                        def.coreGroupIds.filter { it in groupsSet }
                    else emptyList(),
                    counterpartGroupIds = def.depGroupIds + def.coreGroupIds
                )
            }
    }

    /**
     * 从 ComboPlanDefinition.relation 生成出牌编排绑定，去重后返回。
     */
    fun bindings(cardId: String): List<CardComboUseBinding> {
        val groupsSet = groupMap[cardId] ?: emptySet()
        return groupsSet
            .flatMap { comboUseBindingByGroupId[it] ?: emptyList() }
            .distinctBy { "${it.comboId}:${it.beforeGroupIds}:${it.afterGroupIds}" }
    }
}
