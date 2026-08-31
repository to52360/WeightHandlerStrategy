package lin.domain.use.plan

import lin.bean.usePlan.CardComboEntry
import lin.bean.usePlan.CardComboUseBinding
import lin.bean.usePlan.ComboPlanDefinition

/**
 * combo 定义的**运行时索引**（T-012）——让「谓词组」也能参与 combo 评分/互斥/排序约束。
 *
 * ## 为什么需要它
 * `CardCombinedConfig.comboEntries` / `comboUseBindings` 是**启动期静态预算**，由
 * [ComboAssembler] 按静态 `groupMap` 反查生成；而谓词组（`GroupMembership.Predicate`）的
 * 成员在运行时才确定，永远不在 groupMap 里 → combo 若引用谓词组 id，评分加分、
 * coreMutex 剪枝、起手互斥、排序约束装配**全部静默失效**。本索引让运行时也能按
 * 完整组集合（静态 ∪ 谓词）重新推导条目。
 *
 * ## 与静态预算同源，故可全量重算
 * 本索引与 [ComboAssembler] 的静态预算都由 [ComboStep] 在同一次 `contribute` 里用同一份
 * `comboDefinitions` 装配（原子同源）→ 运行时对 `allGroupIds` 全量重算的结果必然是静态
 * 预算的**超集**，不会丢静态部分，无需「预算 ∪ 增量」的第三份合并逻辑。
 *
 * ## 短路与降级
 * - 未装配（index == null）：查询返回空列表。仅发生在未跑装配流程的测试用例。
 * - **消费侧短路**：谓词组未命中时 [lin.bean.ComboCard.runtimeComboEntries] 直接返回静态预算，
 *   根本不进本类——未建谓词组的配置零额外开销。
 * - 本类**不做条件求值**，谓词判定与失败降级在 [GroupMembershipRuntime] 单点收口
 *   （求值失败 = 该组不在集合里），故本类无失败路径。
 */
object ComboRuntime {

    /** 单引用切换即可：[ComboIndex] 构造即索引、之后不可变，故不需要额外快照包装。 */
    @Volatile
    private var index: ComboIndex? = null

    /** 装配期调用（ComboStep）；重复调用会重置，与 [lin.bean.ComboCard] 的每轮重建无关。 */
    fun configure(comboDefinitions: List<ComboPlanDefinition>) {
        index = ComboIndex(comboDefinitions)
    }

    /** 测试用：恢复到未装配状态。 */
    fun clear() {
        index = null
    }

    /** 是否已装配。 */
    fun isEmpty(): Boolean = index == null

    fun entries(groupIds: Set<String>): List<CardComboEntry> =
        index?.entries(groupIds) ?: emptyList()

    fun bindings(groupIds: Set<String>): List<CardComboUseBinding> =
        index?.bindings(groupIds) ?: emptyList()
}
