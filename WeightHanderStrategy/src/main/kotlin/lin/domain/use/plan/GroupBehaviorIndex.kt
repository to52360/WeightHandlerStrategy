package lin.domain.use.plan

import lin.bean.usePlan.*

/**
 * 组级行为的**索引 + 解析**（T-013）——让「谓词组」的组级行为也对成员生效。
 *
 * ## 为什么抽出来（与 T-012 的 [ComboIndex] 同构）
 * OVERRIDE / SURPLUS_GATE 行为原本只由 [GroupBehaviorStep] 在启动期按静态 `groupMap` 展开一次，
 * 存进 `CardCombinedConfig`；谓词组（`GroupMembership.Predicate`）成员运行时才确定、永不在
 * groupMap 里 → **谓词组挂的出牌覆盖 / 余费门槛 / 意图对成员静默失效**。
 *
 * 本类让启动期与运行时**共用同一解析实现**（各方法入参是「组 id 集合」，不关心来源）：
 * - 启动期：`ConfigBindingStep.build` 传静态组集合 → 产出静态预算；
 * - 运行时：[GroupBehaviorRuntime] 传 `allGroupIds`（静态 ∪ 谓词）→ 补上谓词组部分。
 *
 * ⚠️ **三个字段的命中口径不同，勿合并成一个 `resolveOverride`**：
 * 启动期原逻辑里 `conditionalStage` 是「取第一个 conditionalStage 非空的 override」，
 * 而 `useIntent` 是「取第一个非空 override 对象」——若先取 override 再读字段，
 * 「有 override 但 conditionalStage 为空」的组会把后面组的 conditionalStage 挡掉。
 * 故每个字段各自一条解析链，与启动期口径逐字对应。
 *
 * 构造即索引、之后不可变；解析方法无状态、可重复调用。
 */
class GroupBehaviorIndex(
    private val overridesByGroup: Map<String, GroupUseOverride>,
    private val surplusGatesByGroup: Map<String, Int>,
    private val intentDeriver: UseIntentDeriver = UseIntentDeriver()
) {

    /** 取第一个非空 override 对象（服务 useIntent 的 stage/replan/orderWeight 三字段）。 */
    fun resolveOverride(groupIds: Set<String>): GroupUseOverride? =
        groupIds.firstNotNullOfOrNull { overridesByGroup[it] }

    /** 取第一个 conditionalStage 非空的 override（口径见类注释）。 */
    fun resolveConditionalStage(groupIds: Set<String>): ConditionalStageOverride? =
        groupIds.firstNotNullOfOrNull { overridesByGroup[it]?.conditionalStage }

    fun resolveSurplusGate(groupIds: Set<String>): Int? =
        groupIds.firstNotNullOfOrNull { surplusGatesByGroup[it] }

    /**
     * 由「完整组集合」重算该卡的出牌意图。
     *
     * 与 [UseIntentAssembler.assemble] 的唯一差别是组集合来源（静态 vs 静态∪谓词），
     * 输入配方（purposeTags / 组级 override 优先 / 卡级 replan 兜底）完全一致——
     * 故谓词组命中时全量重算的结果必然包含静态部分，不会丢。
     */
    fun resolveUseIntent(
        groupIds: Set<String>,
        purposeTags: Set<PurposeTagId>,
        purposeReplanAfterUse: Boolean
    ): UseIntent {
        val override = resolveOverride(groupIds)
        return intentDeriver.derive(
            CardUseConfig(
                purposeTags = purposeTags,
                stageOverride = override?.stageOverride,
                replanAfterUse = override?.replanAfterUse ?: purposeReplanAfterUse,
                orderWeight = override?.orderWeight
            )
        )
    }
}
