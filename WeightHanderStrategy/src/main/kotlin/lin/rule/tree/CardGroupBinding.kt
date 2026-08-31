package lin.rule.tree

import lin.bean.usePlan.GroupUseOverride

/**
 * 顶层的卡牌分组管理器，维护一套分组方案下的所有 [CardGroupBinding]。
 * [cardGroupManagerId] 对应 DB 主键（UUID String）。
 *
 * @param defaultIncludeDerived 卡组级「是否把卡池外的卡（衍生/发现/随机生成）算进谓词组」默认值。
 *   覆盖链：组级 [GroupMembership.Predicate.includeDerived] > 本字段 > 内建兜底 `false`。
 *   null = 未声明，回落 false。
 */
data class CardGroupManagerConfig(
    val cardGroupManagerId: String,
    val name: String,
    val bindings: List<CardGroupBinding>,
    val enabled: Boolean,
    val defaultIncludeDerived: Boolean? = null
)

/**
 * 分组的成员资格（「这个组包含哪些卡」）。
 *
 * 采用密封类让**判别式由类型系统承载**，而非「type 枚举 + 可空字段」——
 * 后者只是把判别式从 null 换成 type，`conditionId` 对静态组仍须为 null；
 * 密封类下静态组根本没有 conditionId 这个字段，`when` 穷举，编译期保证不遗漏。
 * 与 [CardGroupBehavior] / `EvaluatorLeafConfig` / `ConfigSliceScope` 同款设计。
 *
 * 新增成员类型（如绑定单卡）只需加子类 + 处理各 `when` 站点，主表不动。
 */
sealed class GroupMembership {
    /** 静态组：显式卡列表（JSON 存 card_ids 列）。 */
    data class Static(val cardIds: List<String>) : GroupMembership()

    /**
     * 谓词组：由条件树定义成员，运行期对每张候选卡求值判定。
     *
     * 条件引用 condition_tree_config 的 id（复用 [lin.bean.usePlan.ConditionalStageOverride] 同款范式）。
     * 标准配方：`evaluating_card → to_card → is_card_type(SPELL)`。
     *
     * @param conditionId 条件树 id
     * @param includeDerived 是否纳入**卡池外**的卡（衍生/发现/随机生成）。
     *   - `false`（内建兜底）：只作用于卡池内的卡，语义等价于「启动展开」，
     *     且成员 ⊆ 卡池 → 与启动期预算的 comboEntries 一致，不存在半吊子状态。
     *   - `true`：任何符合条件的卡，含衍生卡。
     *   - `null`：未声明，回落卡组级 `defaultIncludeDerived`（覆盖链见调用方）。
     *   判定依据：`MyWarManage.parseComboCard` 用 `infoMap[card.cardId]`（按卡池构建），
     *   卡池外的卡 `combinedConfig == null`。
     *   ⚠️ 本处 null 表「未声明、回落上层」，是可空配置的标准语义（同 GroupUseOverride），
     *   与「判别式用密封类消除 nullable」是两件不同的事。
     */
    data class Predicate(
        val conditionId: String,
        val includeDerived: Boolean? = null
    ) : GroupMembership()
}

/**
 * 分组行为的密封基类，对应 card_group_behavior 表的一行。
 * 采用与 [EvaluatorLeafConfig] 一致的密封类层次：行为类型由子类本身表达（OVERRIDE / USE_ACTION），
 * 各子类只持有自己的行为数据，不在基类塞所有类型的字段。新增行为类型时加一个子类，
 * 并在所有 `when` 站点处理即可（密封类保证穷尽），[CardGroupBinding] 字段不动。
 *
 * - [OverrideBehavior]：出牌意图覆盖（OVERRIDE 行）。
 * - [UseActionBehavior]：使用动作标识列表（USE_ACTION 行），由引擎 UseActionRegistry 解析为对象。
 */
sealed class CardGroupBehavior {
    /** OVERRIDE 行：分组级出牌行为覆盖，对应 [lin.bean.usePlan.GroupUseOverride]。 */
    data class OverrideBehavior(val override: GroupUseOverride) : CardGroupBehavior()

    /** USE_ACTION 行：使用动作标识列表 + 动态属性，由引擎 UseActionRegistry 解析为 UseStrategy 对象。 */
    data class UseActionBehavior(
        val useActions: List<String>,
        val extraConfig: Map<String, Any> = emptyMap()
    ) : CardGroupBehavior()

    /** SURPLUS_GATE 行：分组级余费门槛 N（D-012 垫后余量：放行 ⟺ 空闲 ≥ 牌费+N）——一类牌统一捏（如解牌组统一 N=2）。
     * 由 [lin.bean.CandidatePolicyFilter.surplusIdleThreshold] 解析链兜底（逐卡小数位 > 分组行为 > tag 默认 > 0）。 */
    data class SurplusGateBehavior(val idleThreshold: Int) : CardGroupBehavior()
}

/**
 * 代表一个具体的卡牌分组，归属于 [managerId] 所在的 Manager。
 * 来源文件（sourceFile）属于 UI/持久化关注点，不在业务层暴露。
 *
 * 分组的行为声明（覆盖 + 使用动作）以 [behaviors] 列表表达，对应 card_group_behavior 表的多行。
 */
data class CardGroupBinding(
    val id: String,
    val managerId: String,
    val name: String,
    /**
     * 成员资格：静态卡列表或条件谓词（详见 [GroupMembership]）。
     *
     * 取成员用 [memberCardIds]（静态组才有确定列表；谓词组运行期求值，此处为空）。
     */
    val membership: GroupMembership,
    val behaviors: List<CardGroupBehavior> = emptyList(),
    val description: String? = null
) {
    /** 静态组的显式卡列表；谓词组返回空列表（成员不在此处）。 */
    val cardIds: List<String>
        get() = when (membership) {
            is GroupMembership.Static -> membership.cardIds
            is GroupMembership.Predicate -> emptyList()
        }
}
