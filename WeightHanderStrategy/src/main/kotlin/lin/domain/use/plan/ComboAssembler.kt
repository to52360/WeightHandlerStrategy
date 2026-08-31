package lin.domain.use.plan

import lin.bean.usePlan.CardComboEntry
import lin.bean.usePlan.CardComboUseBinding
import lin.bean.usePlan.ComboPlanDefinition

/**
 * Combo 编排组装器：持有组合依赖并自索引，自解析 cardId → entries / bindings。
 *
 * 作为栈上实例创建，Task 结束后随帧回收。
 * 消费方：CardConfigBindingTask（启动期组装）。
 * 下游消费者：FindBestCombination（entries）、ComboUseConstraintBuilder（bindings）。
 *
 * **T-012**：映射逻辑已抽到 [ComboIndex]，本类退化为「静态 groupMap → 完整组集合」的适配层。
 * 产出的仍是**静态部分预算**——谓词组命中的部分由 [ComboRuntime] 在运行时补上
 * （见 [lin.bean.ComboCard.runtimeComboEntries]），两者共用同一个 [ComboIndex] 实现，不漂移。
 */
class ComboAssembler(
    private val groupMap: Map<String, Set<String>>,
    comboDefinitions: List<ComboPlanDefinition>
) {
    private val index = ComboIndex(comboDefinitions)

    /** 含 coreMutex 本组过滤 + counterpart 取并集。 */
    fun entries(cardId: String): List<CardComboEntry> =
        index.entries(groupMap[cardId] ?: emptySet())

    /** 从 ComboPlanDefinition.relation 生成出牌编排绑定，去重后返回。 */
    fun bindings(cardId: String): List<CardComboUseBinding> =
        index.bindings(groupMap[cardId] ?: emptySet())
}
