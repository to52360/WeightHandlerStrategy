package lin.domain.use.plan

import lin.bean.usePlan.ConditionalStageOverride
import lin.bean.usePlan.GroupUseOverride
import lin.bean.usePlan.PurposeTagId
import lin.bean.usePlan.UseIntent

/**
 * 组级行为的**运行时索引**（T-013）——谓词组挂的行为对成员生效的运行时入口。
 *
 * 与 [ComboRuntime] 同构：装配期由 `ConfigBindingStep.build` 用**同一份数据**同时产出
 * 静态预算与运行时索引（同源原子）→ 运行时对 `allGroupIds` 全量重算的结果必是静态预算的
 **超集**，不丢静态部分，故不需要第三份「预算 ∪ 增量」合并逻辑。
 *
 * 本类只做转发，全部解析逻辑在 [GroupBehaviorIndex]（启动期与运行时共用）。
 *
 * - 未装配（index == null）：查询返回 null。仅发生在未跑装配流程的测试用例。
 * - **消费侧短路**：谓词组未命中时 [lin.bean.ComboCard] 直接返回静态预算，根本不进本类——
 *   未建谓词组的配置零额外开销。
 */
object GroupBehaviorRuntime {

    /** 单引用切换即可：[GroupBehaviorIndex] 构造即索引、之后不可变。 */
    @Volatile
    private var index: GroupBehaviorIndex? = null

    /** 装配期调用（`ConfigBindingStep.build`）；重复调用会重置。 */
    fun configure(index: GroupBehaviorIndex) {
        this.index = index
    }

    /** 测试用：恢复到未装配状态。 */
    fun clear() {
        index = null
    }

    /**
     * 索引是否已装配、可提供重算，语义同 [ComboRuntime.isReady]——
     * 未装配时消费方须回落静态预算，而非当成「无组级行为」。
     */
    fun isReady(): Boolean = index != null

    fun resolveOverride(groupIds: Set<String>): GroupUseOverride? =
        index?.resolveOverride(groupIds)

    fun resolveConditionalStage(groupIds: Set<String>): ConditionalStageOverride? =
        index?.resolveConditionalStage(groupIds)

    fun resolveSurplusGate(groupIds: Set<String>): Int? =
        index?.resolveSurplusGate(groupIds)

    fun resolveUseIntent(
        groupIds: Set<String>,
        purposeTags: Set<PurposeTagId>,
        purposeReplanAfterUse: Boolean
    ): UseIntent? = index?.resolveUseIntent(groupIds, purposeTags, purposeReplanAfterUse)
}
