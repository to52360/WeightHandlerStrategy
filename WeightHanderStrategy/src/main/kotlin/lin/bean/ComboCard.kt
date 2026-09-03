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
import lin.domain.use.plan.ComboRuntime
import lin.domain.use.plan.GroupBehaviorRuntime
import lin.domain.use.plan.GroupMembershipRuntime


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
     * 谓词组（条件定义成员的分组）的运行时判定结果。
     *
     * **per-实例缓存一次**：ComboCard 每轮重建、属性快照固定，故这是正确的缓存粒度——
     * 既保证「被减费的卡」拿到当前费用下的判定结果（按 cardId 缓存会拿到减费前的旧结果，
     * 条件涉及费用时静默错判），又做到每轮每卡只求值一次。
     *
     * **空集就是「不需重算」的统一信号**：卡组没定义谓词组时求值器内部短路恒空，
     * 与「定义了但这张卡没命中」在下游**完全等价**——都直读静态预算。故下游只需这一个
     * 数据守卫，不需要额外的「模式」概念层（2026-09-03 删除 GroupRuntimeMode 体系的依据）。
     */
    val predicateGroupIds: Set<String> by lazy {
        GroupMembershipRuntime.resolve(this, isDerived = combinedConfig == null)
    }

    /**
     * 完整分组归属 = 静态组（启动期预算）∪ 谓词组（运行时求值），per-实例缓存一次。
     *
     * 全体「这张卡属于哪些组」读取入口的统一底座——[lin.bean.groupIds] / [lin.bean.hasGroup] /
     * [lin.bean.hasAnyGroup]、`group_filter` 算子、`UsePlanOrderer` 约束比较等高频读取
     * 都收敛到这里，不再各自重复分配合并 Set。
     *
     * **未来新增组来源（临时组等）在此追加一项 `+` 即可**——每个来源自己管「有没有贡献」，
     * 空集自动不参与，无需新增分支或类型（并集天然可组合，不会退化成继承/组合爆炸）。
     */
    val allGroupIds: Set<String> by lazy {
        val static = combinedConfig?.groupIds.orEmpty()
        val dynamic = predicateGroupIds
        if (dynamic.isEmpty()) static else static + dynamic
    }

    /**
     * 以下五个字段 =「静态预算 ∪ 谓词组命中」的运行时结果：
     *
     * **谓词判定只在 [predicateGroupIds] 发生一次**，五者共享该结果派生——不重复求值
     * （防性能退化），也不建聚合 Context（防伪包装）。
     *
     * 命中时对 [allGroupIds] **全量重算**——因与静态预算同源（见
     * [lin.utils.startup.ComboStep] / [lin.utils.startup.ConfigBindingStep]），
     * 结果是静态预算的超集，故不需要第三份合并逻辑。
     *
     * @defect combo-plan-model/K-001: 这 5 项是 [allGroupIds] 的纯函数（Map 查表），却各套一个 lazy
     *   ——每卡每轮多 5 个 Lazy 对象分配 + 5 次锁，静态模式下缓存零收益（只返回静态预算引用）。
     *   真正需要缓存的只有 [predicateGroupIds]（条件树求值）与 [allGroupIds]（Set 合并）两项。
     *   收敛前须实测 `FindBestCombination` 循环内 `comboEntries` 的访问频次（与 Q-012 联动）。
     */

    /** combo 条目（评分 / coreMutex）：谓词组命中时补全静态预算缺的部分。 */
    val comboEntries: List<CardComboEntry> by lazy {
        if (shouldRecomputeCombo()) ComboRuntime.entries(allGroupIds)
        else combinedConfig?.comboEntries.orEmpty()
    }

    /** 出牌顺序绑定，同 [comboEntries] 的守卫与短路语义。 */
    val comboUseBindings: List<CardComboUseBinding> by lazy {
        if (shouldRecomputeCombo()) ComboRuntime.bindings(allGroupIds)
        else combinedConfig?.comboUseBindings.orEmpty()
    }

    /**
     * 条件化阶段覆盖：谓词组挂的 conditionalStage 对成员生效。
     *
     * 重算基于 [allGroupIds]（静态在前、谓词在后），故**静态组声明优先于谓词组**。
     * 口径与启动期**逐字一致**（取第一个 conditionalStage 非空的 override，而非先取 override
     * 再读字段）——否则「有 override 但 conditionalStage 为空」的组会挡掉后面组的条件覆盖。
     */
    val conditionalStage: ConditionalStageOverride? by lazy {
        if (shouldRecomputeBehavior()) GroupBehaviorRuntime.resolveConditionalStage(allGroupIds)
        else combinedConfig?.conditionalStage
    }

    /** 组级余费门槛 N：谓词组挂的 SURPLUS_GATE 对成员生效。 */
    val groupSurplusIdleThreshold: Int? by lazy {
        if (shouldRecomputeBehavior()) GroupBehaviorRuntime.resolveSurplusGate(allGroupIds)
        else combinedConfig?.groupSurplusIdleThreshold
    }

    /**
     * 出牌意图：谓词组挂的 OVERRIDE（stageOverride / replanAfterUse / orderWeight）对成员生效。
     *
     * 重算输入 = 静态快照（[CardCombinedConfig.purposeTags] / `purposeReplanAfterUse`）
     * + 完整组集合下的组级 override，配方与启动期 [lin.domain.use.plan.UseIntentAssembler] 一致。
     * `combinedConfig` 可为 null（衍生卡 / 卡池外）——此时用空卡级输入，组级 override 仍应生效。
     */
    val useIntent: UseIntent? by lazy {
        val config = combinedConfig
        if (shouldRecomputeBehavior()) {
            GroupBehaviorRuntime.resolveUseIntent(
                allGroupIds,
                config?.purposeTags ?: emptySet(),
                config?.purposeReplanAfterUse ?: false
            ) ?: config?.useIntent
        } else {
            config?.useIntent
        }
    }

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
     *
     * @defect combo-plan-model/K-002: 本方法是**属性初始化器**（[useAfterStrategy] /
     *   [useBeforeStrategy]），在构造期执行 → 每张 ComboCard 构造即跑，与 lazy 惰性设计相悖。
     *   当前被 `config.groupStrategies.isNotEmpty()` 短路挡住（未配组级 USE_ACTION 就不触发），
     *   一旦配了「谓词组 + 组级 USE_ACTION」，**每张卡每次构造都会强制求值全部谓词组**。
     *   修法：改 `by lazy` 委托（合并逻辑不变）。
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

    // @defect combo-plan-model/K-003: **半死字段——写入侧活跃、读取侧已断**。
    //   写入方：BaseConfig / CardAction / RuleTreeBinding / ConfigHandler（后者明注「用户暂不删」）；
    //   读取方：无任何排序/权重消费方（CleanWar 注释「已无排序消费方」）。
    //   故**不能只删本字段**——须连带清理上述 4 处写入 + CardWeightInfo 字段 + 常量
    //   （DefUseGroupId / LastUseGroupId / FirstUseGroupId / DefUseGroupOrder），属 legacy 清理范畴。
    //   另 toString() 仍在打印这两个字段。
    // T-002：旧排序通道弃用（同 CardWeightInfo.useGroupId），仅保留兼容写入；排序归属于 UseStage/stageOverride。
    var useGroupId: Int = cardWeightInfo?.useGroupId ?: DefUseGroupId

    // T-002：旧排序通道弃用（同 useGroupId）；不再参与任何排序/权重（T-009 已断 addWeight 污染）。
    var useGroupOrder: Double = baseValue

    // 出牌权重（最终决策依据）：powerWeight = baseValue（基础价值） + extPowerWeight（战术溢价）。
    val powerWeight: Double
        get() = baseValue + extPowerWeight

    // 运行时加分累加器，初值 = BaseWeight（T-041 归零为 0.0，即无初始常数补贴）。
    // 注意它会进主搜索的 currentWeight，所以初值每 +1 就等于给「每张入选的牌」发 1 分线性补贴
    // ——与 comboPenalty（防堆砌）冲突，详见 EngineConfig.baseWeight 注释。
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

    /**
     * 重置为初始值。
     *
     * ⚠️ T-041：当前**无生产调用方**——reLoad 会重建 ComboCard，重置由重建天然完成。
     * 保留仅为未来「原地复用实例」场景；删除前请先确认无扩展方依赖。
     */
    fun cleanWeight() {
        extPowerWeight = BaseWeight
    }

    /**
     * 判断当前是否处于"基础分"状态：extPowerWeight 尚未被任何规则 addWeight 加分。
     * baseValue（基础价值）是构造时注入的基础分，不属于"规则加分"，故只看 extPowerWeight。
     *
     * ⚠️ T-041 语义变化：BaseWeight 归零后本方法退化为 `extPowerWeight == 0.0`——
     * 若某组规则的加减分恰好抵消回 0，会**误判为「未被加分」**（旧值 1.0 时该误判概率低得多）。
     * 当前**无生产调用方**（HaloRule 仅在 KDoc 引用本符号，代码未调用），故无实际影响；
     * 若未来要启用，应改为显式标记（Boolean 脏位）而非数值判等。
     */
    fun isBaseWeight(): Boolean {
        return extPowerWeight == BaseWeight
    }

    /**
     * 战场相关
     */
    fun toDie() = cardWeightInfo?.toDie ?: false


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
     *
     * @defect combo-plan-model/K-004: **equals 单向成立 + hashCode 不一致**。
     *   `comboCard == card` 为 true，但 `card == comboCard` 为 false（Card.equals 不认 ComboCard）；
     *   且 hashCode 与 Card.hashCode() 不同 → 跨类型混装的 HashMap/HashSet 行为不可靠。
     *   当前未发作（疑似无跨类型混装的集合），属定时炸弹。修法：去掉 `is Card` 分支，
     *   或提供显式 `sameEntity(other)` 替代跨类型相等语义。
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

/**
 * 命中谓词组 **且** combo 运行时索引已装配——两者皆真才值得重算。
 *
 * [ComboCard.predicateGroupIds] 为空时（卡组无谓词组 / 这张卡没命中）直读静态预算：
 * 静态预算是启动期按静态组算好的，不重算也不会缺。
 *
 * 索引未装配时同样回落静态预算——此时重算只能拿到空结果，反而**丢掉启动期已算好的
 * 静态部分**。最坏情况应是「退化成纯静态」，而不是「卡的一半配置静默消失」。
 */
private fun ComboCard.shouldRecomputeCombo(): Boolean =
    predicateGroupIds.isNotEmpty() && ComboRuntime.isReady()

/** 同 [shouldRecomputeCombo]，对应组级行为索引（两条链各看各的装配状态）。 */
private fun ComboCard.shouldRecomputeBehavior(): Boolean =
    predicateGroupIds.isNotEmpty() && GroupBehaviorRuntime.isReady()
