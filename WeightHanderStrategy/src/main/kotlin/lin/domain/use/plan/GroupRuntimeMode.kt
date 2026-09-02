package lin.domain.use.plan

import lin.bean.ComboCard
import lin.bean.usePlan.CardComboEntry
import lin.bean.usePlan.CardComboUseBinding
import lin.bean.usePlan.ConditionalStageOverride
import lin.bean.usePlan.UseIntent

/**
 * 卡组级「分组运行模式」——**构造期分发，查询期零模式分支**（T-014，SICP 数据导向）。
 *
 * 谓词组（[lin.rule.tree.GroupMembership.Predicate]）落地后，[ComboCard] 的运行时读取存在两模式，
 * 本接口是它们的统一策略：**模式在装配期由 [GroupMembershipRuntime.configure] 按 defs 是否为空
 * 选定一次**（写入 [GroupRuntimeModes]），ComboCard 的 7 个 lazy 字段只做纯委托——查询路径上
 * 不再出现「if 静态 else 谓词」的模式判别。**删光谓词组重新装载即自动切回静态模式。**
 *
 * 各实现的方法均无状态：per-实例缓存由 ComboCard 的 lazy 字段承载，本层不做任何缓存。
 */
interface GroupRuntimeMode {

    /** 谓词组成员判定（静态模式恒空集）。 */
    fun predicateGroupIds(card: ComboCard): Set<String>

    /** 完整分组归属 = 静态组 ∪ 谓词组。 */
    fun allGroupIds(card: ComboCard): Set<String>

    /** combo 条目（评分 / coreMutex）。 */
    fun comboEntries(card: ComboCard): List<CardComboEntry>

    /** 出牌顺序绑定。 */
    fun comboUseBindings(card: ComboCard): List<CardComboUseBinding>

    /** 条件化阶段覆盖。 */
    fun conditionalStage(card: ComboCard): ConditionalStageOverride?

    /** 组级余费门槛 N。 */
    fun groupSurplusIdleThreshold(card: ComboCard): Int?

    /** 出牌意图。 */
    fun useIntent(card: ComboCard): UseIntent?
}

/**
 * **静态模式**（卡组不存在谓词组）：七法直读 `combinedConfig` 静态预算，`predicateGroupIds` 恒空——
 * 即谓词组特性落地前（T-002 之前）的最初行为。查询路径零谓词分支、零额外计算（返回静态预算引用）。
 */
object StaticGroupRuntimeMode : GroupRuntimeMode {

    override fun predicateGroupIds(card: ComboCard): Set<String> = emptySet()

    override fun allGroupIds(card: ComboCard): Set<String> =
        card.combinedConfig?.groupIds.orEmpty()

    override fun comboEntries(card: ComboCard): List<CardComboEntry> =
        card.combinedConfig?.comboEntries.orEmpty()

    override fun comboUseBindings(card: ComboCard): List<CardComboUseBinding> =
        card.combinedConfig?.comboUseBindings.orEmpty()

    override fun conditionalStage(card: ComboCard): ConditionalStageOverride? =
        card.combinedConfig?.conditionalStage

    override fun groupSurplusIdleThreshold(card: ComboCard): Int? =
        card.combinedConfig?.groupSurplusIdleThreshold

    override fun useIntent(card: ComboCard): UseIntent? =
        card.combinedConfig?.useIntent
}

/**
 * **谓词模式**（卡组存在谓词组）：现行合并逻辑原样收编——
 * per-card 命中短路（**特性逻辑，不是模式判别**：谓词模式下绝大多数卡也不命中，未命中须直返静态预算
 * 零计算）+ 命中后对 `allGroupIds` 全量重算（与静态预算同源，结果必是超集，详见 T-012/T-013）。
 *
 * 条件求值与失败降级在 [GroupMembershipRuntime] 单点收口；combo / 组级行为重算分别经
 * [ComboRuntime] / [GroupBehaviorRuntime]（两者只在谓词模式下被消费）。
 */
object PredicateGroupRuntimeMode : GroupRuntimeMode {

    override fun predicateGroupIds(card: ComboCard): Set<String> =
        GroupMembershipRuntime.resolve(card, isDerived = card.combinedConfig == null)

    override fun allGroupIds(card: ComboCard): Set<String> {
        val static = card.combinedConfig?.groupIds.orEmpty()
        val dynamic = card.predicateGroupIds
        // 绝大多数卡不在任何谓词组里，短路掉 Set 合并的分配开销（数据合并短路，非模式判别）
        return if (dynamic.isEmpty()) static else static + dynamic
    }

    override fun comboEntries(card: ComboCard): List<CardComboEntry> =
        if (card.predicateGroupIds.isEmpty()) {
            card.combinedConfig?.comboEntries.orEmpty()
        } else {
            ComboRuntime.entries(card.allGroupIds)
        }

    override fun comboUseBindings(card: ComboCard): List<CardComboUseBinding> =
        if (card.predicateGroupIds.isEmpty()) {
            card.combinedConfig?.comboUseBindings.orEmpty()
        } else {
            ComboRuntime.bindings(card.allGroupIds)
        }

    override fun conditionalStage(card: ComboCard): ConditionalStageOverride? =
        if (card.predicateGroupIds.isEmpty()) {
            card.combinedConfig?.conditionalStage
        } else {
            GroupBehaviorRuntime.resolveConditionalStage(card.allGroupIds)
        }

    override fun groupSurplusIdleThreshold(card: ComboCard): Int? =
        if (card.predicateGroupIds.isEmpty()) {
            card.combinedConfig?.groupSurplusIdleThreshold
        } else {
            GroupBehaviorRuntime.resolveSurplusGate(card.allGroupIds)
        }

    override fun useIntent(card: ComboCard): UseIntent? {
        val config = card.combinedConfig
        return if (card.predicateGroupIds.isEmpty()) {
            config?.useIntent
        } else {
            // 命中谓词组即全量重算。config 可为 null（衍生卡 / 卡池外）——此时用空卡级输入，
            // 组级 override 仍应生效（与衍生卡经谓词组参与 combo 的处理一致）。
            GroupBehaviorRuntime.resolveUseIntent(
                card.allGroupIds,
                config?.purposeTags ?: emptySet(),
                config?.purposeReplanAfterUse ?: false
            ) ?: config?.useIntent
        }
    }
}

/**
 * 模式持有者：单 `@Volatile` 引用切换（两实现均无状态，切换无中间态）。
 * 装配期由 [GroupMembershipRuntime.configure] 翻转；未装配时默认静态模式。
 */
object GroupRuntimeModes {

    @Volatile
    var current: GroupRuntimeMode = StaticGroupRuntimeMode
}
