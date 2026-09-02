package lin.bean


import club.xiaojiawei.hsscriptcardsdk.bean.Card
import lin.bean.usePlan.CardComboEntry
import lin.bean.usePlan.CardComboUseBinding
import lin.bean.usePlan.ConditionalStageOverride
import lin.bean.usePlan.UseIntent
import lin.domain.context.BaseWeight
import lin.domain.context.NotWeight
import lin.domain.context.UnUseWeight
import lin.domain.use.UseAfterStrategy
import lin.domain.use.UseBeforeStrategy
import lin.domain.use.UseStrategy
import lin.domain.use.plan.GroupRuntimeModes


typealias ComboRule = (ComboCard) -> Double


/**
 *
 * todo-future 1.为了快速实现,弄了个上帝类出来,有空再重构 2.为了方便使用弄了很多方法(应该用扩展方法去扩展)
 *
 * 防重复提醒：新增读取入口前，先全局搜 `fun ComboCard.xxx` 与 `ComboCard.xxx()` 是否已有实现。
 * 同一入口禁止同时保留「成员方法 + 扩展函数」两份——Kotlin 中成员方法会 shadow 同名扩展，
 * 触发 "extension is shadowed by a member" 警告（详见 ComboCard-Config-Access.md 的配置访问边界）。
 * 跨领域高频读取应放 ComboCardConfigAccess.kt 扩展；跨包消费 / 需特定可空语义才放成员方法。
 *
 */
class ComboCard(
    val combinedConfig: CardCombinedConfig? = null,
    val card: Card,
    // 基础价值（固有物理价值）：配置等效费用分 / 随从身材分 / 法术费用兜底分 三分流结果，
    // 由 MyWarManage.parseComboCard 经 calcBaseValue 一次性计算注入，与运行时战术分（extPowerWeight）正交叠加。
    // 最终出牌权重：powerWeight = baseValue + extPowerWeight。
    val baseValue: Double = 0.0
) {

    val cardWeightInfo = combinedConfig?.weightInfo

    /**
     * 谓词组（条件定义成员的分组，T-002）的运行时判定结果。
     *
     * **per-实例缓存一次**：ComboCard 每轮重建、属性快照固定，故这是正确的缓存粒度——
     * 既保证「被减费的卡」拿到当前费用下的判定结果（按 cardId 缓存会拿到减费前的旧结果，
     * 条件涉及费用时静默错判），又做到每轮每卡只求值一次。
     *
     * 静态模式下（卡组无谓词组，T-014 构造期分发）恒空集，根本不进求值器；
     * 静态组成员在 [CardCombinedConfig.groupIds]（启动期预算），两者由 `groupIds()` 合并。
     */
    val predicateGroupIds: Set<String> by lazy {
        GroupRuntimeModes.current.predicateGroupIds(this)
    }

    /**
     * 完整分组归属 = 静态组（启动期预算）∪ 谓词组（运行时求值），per-实例缓存一次。
     *
     * [lin.bean.groupIds] 等读取入口的统一底座（缓存需要字段承载，故放本体）。
     * `UsePlanOrderer` 约束比较、`group_filter` 算子等高频读取不再重复分配合并 Set。
     * 静态模式下直读静态组，零合并分支。
     */
    val allGroupIds: Set<String> by lazy {
        GroupRuntimeModes.current.allGroupIds(this)
    }

    /**
     * 运行时 combo 条目 = 静态预算 ∪ 谓词组命中（T-012）。
     *
     * **谓词判定只在 [predicateGroupIds] 发生一次**，本字段与 [runtimeComboUseBindings] 共享
     * 该结果派生——不重复求值（防性能退化），也不建聚合 Context（防伪包装）。
     *
     * 模式分发见 [GroupRuntimeModes]（T-014）：静态模式直读静态预算引用（零额外计算）；
     * 谓词模式下命中时对 [allGroupIds] **全量重算**——因与静态预算同源
     * （见 [lin.utils.startup.ComboStep]），结果是静态预算的超集，故不需要第三份合并逻辑；
     * coreMutex 的「本组过滤」随之覆盖谓词组，互斥判定自动生效。
     */
    val runtimeComboEntries: List<CardComboEntry> by lazy {
        GroupRuntimeModes.current.comboEntries(this)
    }

    /** 出牌顺序绑定，同 [runtimeComboEntries] 的模式分发与短路语义（T-012）。 */
    val runtimeComboUseBindings: List<CardComboUseBinding> by lazy {
        GroupRuntimeModes.current.comboUseBindings(this)
    }

    /**
     * 运行时条件化阶段覆盖（T-013）：谓词组挂的 conditionalStage 对成员生效。
     *
     * 谓词模式下全量重算基于 [allGroupIds]（静态在前、谓词在后），故**静态组声明优先于谓词组**。
     * 口径与启动期**逐字一致**（取第一个 conditionalStage 非空的 override，而非先取 override
     * 再读字段）——否则「有 override 但 conditionalStage 为空」的组会挡掉后面组的条件覆盖。
     */
    val runtimeConditionalStage: ConditionalStageOverride? by lazy {
        GroupRuntimeModes.current.conditionalStage(this)
    }

    /** 运行时组级余费门槛 N（T-013）：谓词组挂的 SURPLUS_GATE 对成员生效。 */
    val runtimeGroupSurplusIdleThreshold: Int? by lazy {
        GroupRuntimeModes.current.groupSurplusIdleThreshold(this)
    }

    /**
     * 运行时出牌意图（T-013）：谓词组挂的 OVERRIDE（stageOverride / replanAfterUse /
     * orderWeight）对成员生效。
     *
     * 重算输入 = 静态快照（[CardCombinedConfig.purposeTags] / `purposeReplanAfterUse`）
     * + 完整组集合下的组级 override，配方与启动期 [lin.domain.use.plan.UseIntentAssembler] 一致。
     */
    val runtimeUseIntent: UseIntent? by lazy {
        GroupRuntimeModes.current.useIntent(this)
    }

    /** T-013：改读运行时结果（静态预算 ∪ 谓词组行为），详见 [runtimeUseIntent]。 */
    fun useIntent() = runtimeUseIntent

    /** T-013：条件化阶段覆盖，详见 [runtimeConditionalStage]。 */
    fun conditionalStage() = runtimeConditionalStage

    /** T-013：组级余费门槛 N，详见 [runtimeGroupSurplusIdleThreshold]。 */
    fun groupSurplusIdleThreshold() = runtimeGroupSurplusIdleThreshold

    /** T-012：改读运行时合并结果（静态预算 ∪ 谓词组命中），详见 [runtimeComboEntries]。 */
    fun comboEntries() = runtimeComboEntries

    /** T-012：同 [comboEntries]，详见 [runtimeComboUseBindings]。 */
    fun comboUseBindings() = runtimeComboUseBindings

    val combo = cardWeightInfo?.combos
    //指定目标
    var pointCard: Card? = null


    fun groupId() = cardWeightInfo?.groupId
    //换牌策略
    val changeComboRule
        get() = cardWeightInfo?.changeComboRule

    //基础信息
    fun cardId() = card.cardId
    fun cost() = card.cost
    //select 暂定直接修改,缺点:状态修改到处是无法追踪,要验证状态变化将很复杂,
    /**
     * 合并配置侧声明动作，before/after 按类型分流；无数据则为 null，惰性创建避免空列表分配。
     *
     * 两个来源：
     * - `combinedConfig.useStrategies`：cardId 级，启动期由 expandSlices 展开；
     * - `combinedConfig.groupStrategies`：组级，仅谓词组（成员运行时判定才知），
     *   此处用 [predicateGroupIds] 判定归属后合并（T-002）。
     */
    private inline fun <reified T : UseStrategy> mergeStrategies(): MutableList<T>? {
        val config = combinedConfig ?: return null
        var result: MutableList<T>? = null

        // 静态：cardId 级（启动期 expandSlices 展开）
        for (s in config.useStrategies) {
            if (s is T) {
                if (result == null) result = mutableListOf()
                result.add(s)
            }
        }

        // 谓词组级：成员运行时判定，此处按 groupId 归属合并。先短路掉绝大多数卡。
        if (config.groupStrategies.isNotEmpty() && predicateGroupIds.isNotEmpty()) {
            for (groupId in predicateGroupIds) {
                for (s in config.groupStrategies[groupId].orEmpty()) {
                    if (s is T) {
                        if (result == null) result = mutableListOf()
                        result.add(s)
                    }
                }
            }
        }
        return result
    }

    var useAfterStrategy: MutableList<UseAfterStrategy>? = mergeStrategies()
    var useBeforeStrategy: MutableList<UseBeforeStrategy>? = mergeStrategies()

    // T-002：旧排序通道弃用（同 CardWeightInfo.useGroupId），仅保留兼容写入；排序归属于 UseStage/stageOverride。
    var useGroupId: Int = cardWeightInfo?.useGroupId ?: DefUseGroupId

    // T-002：旧排序通道弃用（同 useGroupId）；不再参与任何排序/权重（T-009 已断 addWeight 污染）。
    var useGroupOrder: Double = baseValue

    // 出牌权重（最终决策依据）：powerWeight = baseValue（基础价值） + extPowerWeight（战术溢价）。
    val powerWeight: Double
        get() = baseValue + extPowerWeight
    var extPowerWeight: Double = BaseWeight

    // 评估树战术信号（D-007 回归「树分皆战术信号」：全树分 general+tactical，由 weightEvaluator 写入）。
    // 消费方：第一轮候选门控（T-008）、余费门槛绕行、fillValue 溢价（×TacticalScoreScale，封顶 G）。
    // Q-008/T-007 的通道分离已无独立消费者（双费数模型取代其使命），通道字段遗留待清理（T-018）。
    var tacticalScore: Double = 0.0

    /**
     * 权重累加方法
     */
    fun addWeight(weight: Double) {
        extPowerWeight += weight
        // D-003/T-009：断开 useGroupOrder 污染。useGroupOrder 是排序意图（UseIntent/配置），
        // 与运行时权重（extPowerWeight）正交；运行时分数变化不再隐式改写排序。
    }

    fun cleanWeight() {
        extPowerWeight = BaseWeight
    }

    /**
     * 判断当前是否处于"基础分"状态：extPowerWeight 尚未被任何规则 addWeight 加分。
     * baseValue（基础价值）是构造时注入的基础分，不属于"规则加分"，故只看 extPowerWeight。
     * （注：基础价值基于 D-013 统一叠加模型无条件生效，不再依赖此方法判断）。
     */
    fun isBaseWeight(): Boolean {
        return extPowerWeight == BaseWeight
    }

    /**
     * 战场相关
     */
    fun toDie() = cardWeightInfo?.toDie ?: false


    //todo 同组加权现在怎么处理 在同一组会增加权重
    fun comboAddWeight(comboCard: ComboCard): Double {
        var weight = NotWeight
        combo?.forEach {
            weight += it.comboProcess(this, comboCard)
        }
        return weight
    }


    /**
     * todo-future 还需引入策略(全局策略,组策略,卡策略,来解决能不能使用),什么情况卖,什么情况不卖
     * 暂时 小于0为不可使用
     */
    fun canUse(): Boolean = powerWeight >= NotWeight
    fun unUse() {
        extPowerWeight = UnUseWeight
    }
    fun isUnUse(): Boolean {
        // T-009：移除「负权重改 LastUseGroupId」副作用——那是旧的"负权重要最后使用"临时方案，
        // 多重语义污染（isUnUse 本应只做不可用判断）。负分是软惩罚（评估树返回），由候选过滤
        // （T-008 passesFirst/SecondRoundCandidate）在候选层承担，不在判断方法里改排序状态。
        return extPowerWeight == UnUseWeight
    }



    /**
     * 获取指定权重
     */
    fun getExpectWeight(expectWeight: Double): Double {
        return expectWeight - powerWeight
    }

    /**
     * todo-future  用于处理重新生成comboCard时候判断是否重复,重新生成没有这么复杂的逻辑,但是效率有问题
     *  这样操作其他比较会不会有问题?
     */
    override fun equals(other: Any?): Boolean {
        //select 比较cardId还是entityId,没有想清楚,先
        return other?.let {
            this === other || when (other) {
                is ComboCard -> {
                    card.entityId == other.card.entityId
                }

                is Card -> card.entityId == other.entityId
                else -> false
            }
        } ?: false
    }

    override fun hashCode(): Int {

        return card.entityId.hashCode() * 31
    }

    override fun toString(): String {
        if (card.entityName.startsWith("UNK"))
            return "{id=${cardId()},weight=${powerWeight},useGroupId=${useGroupId},useGroupOrder=${useGroupOrder}}"
        return "{id=${cardId()},name=${card.entityName},weight=${powerWeight},useGroupId=${useGroupId},useGroupOrder=${useGroupOrder}"
    }

}
