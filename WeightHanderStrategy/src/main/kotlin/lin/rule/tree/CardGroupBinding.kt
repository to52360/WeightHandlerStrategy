package lin.rule.tree

import lin.bean.usePlan.GroupUseOverride

/**
 * 顶层的卡牌分组管理器，维护一套分组方案下的所有 [CardGroupBinding]。
 * [cardGroupManagerId] 对应 DB 主键（UUID String）。
 */
data class CardGroupManagerConfig(
    val cardGroupManagerId: String,
    val name: String,
    val bindings: List<CardGroupBinding>,
    val enabled: Boolean
)

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
    val cardIds: List<String>,
    val behaviors: List<CardGroupBehavior> = emptyList(),
    val description: String? = null
)
