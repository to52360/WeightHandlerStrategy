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
    // 由 MyWarManage.parseComboCard 经 calcBaseValue 一次性计算注入，与运行时战术溢价（extPowerWeight）正交叠加。
    // 最终出牌权重：powerWeight = baseValue + extPowerWeight。
    // 【量纲：分】—— 费经 `costValue(c) = 3.0·c^0.5`（[lin.domain.context.costValue]）**非线性凹映射**后的输出。
    // A-合流版（D-FO-005 / T-FO-014）后 extPowerWeight 亦为分（树分/光环分经
    // [lin.domain.context.tacticalContribution] 换算）⇒ 同轴直加合法，跨轴问题已修复。
    val baseValue: Double = 0.0,
    // T-FO-017 法术锚点统一：**数据库初始费**（非实时费），仅「无配置法术」兜底换算用。
    // 同一份值同时喂两条链——[baseValue] 的法术分支（[lin.weightHandler.calcBaseValue]）与
    // [equivalentCostValue] 的法术分支——使「基础分」与「战术换算锚点」共用同一 E（原先一个用
    // 初始费、一个用实时费，被减费到 0 的法术战术贡献可反超整卡基础分）。
    // 由 MyWarManage.parseComboCard 注入其已有的 baseCost 缓存；随从/已配置牌不适用 ⇒ 保持 0。
    // 配置等效费存在时本字段不生效（两处分支都先取配置值）。
    // ⚠️ 技能（MyWarManage.parseSkillCard）刻意**不注入**本参数：其缺省注入的配置等效费恒为 1.0 /
    //    配置态恒 > 0，等效费分支先返回 ⇒ 拿不到本字段（保持 0 无行为差异），且技能非「法术兜底」语义。
    val initialCost: Int = 0
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
     * @defect combo-plan-model/K-001（已修复 2026-09-08）: 这 5 项原各套一个 lazy——每卡每轮
     *   多 5 个 Lazy 对象分配 + 5 次锁，静态模式下缓存零收益（只返回静态预算引用）。已去 lazy
     *   改计算属性：重活在 [predicateGroupIds]（条件树求值）与 [allGroupIds]（Set 合并）两项
     *   lazy 缓存里，本层只是 Map 查表派生；访问频次低（门控/装配期，非回溯热循环），重复查表可接受。
     */

    /** combo 条目（评分 / coreMutex）：谓词组命中时补全静态预算缺的部分。 */
    val comboEntries: List<CardComboEntry>
        get() = if (shouldRecomputeCombo()) ComboRuntime.entries(allGroupIds)
        else combinedConfig?.comboEntries.orEmpty()

    /** 出牌顺序绑定，同 [comboEntries] 的守卫与短路语义。 */
    val comboUseBindings: List<CardComboUseBinding>
        get() = if (shouldRecomputeCombo()) ComboRuntime.bindings(allGroupIds)
        else combinedConfig?.comboUseBindings.orEmpty()

    /**
     * 条件化阶段覆盖：谓词组挂的 conditionalStage 对成员生效。
     *
     * 重算基于 [allGroupIds]（静态在前、谓词在后），故**静态组声明优先于谓词组**。
     * 口径与启动期**逐字一致**（取第一个 conditionalStage 非空的 override，而非先取 override
     * 再读字段）——否则「有 override 但 conditionalStage 为空」的组会挡掉后面组的条件覆盖。
     */
    val conditionalStage: ConditionalStageOverride?
        get() = if (shouldRecomputeBehavior()) GroupBehaviorRuntime.resolveConditionalStage(allGroupIds)
        else combinedConfig?.conditionalStage

    /** 组级余费门槛 N：谓词组挂的 SURPLUS_GATE 对成员生效。 */
    val groupSurplusIdleThreshold: Int?
        get() = if (shouldRecomputeBehavior()) GroupBehaviorRuntime.resolveSurplusGate(allGroupIds)
        else combinedConfig?.groupSurplusIdleThreshold

    /**
     * 余费门槛 N：覆盖链（T-FO-015）的**唯一读取入口**——所有消费方（第一轮门控 / 余费门 /
     * 绝望前置 / 日志）一律读本字段，不再各自拼链（编排点见 [resolveIdleThreshold]）。
     *
     * `by lazy` = 构造后按需烘焙一次（粒度同 [predicateGroupIds]：每轮每卡一次）。
     * **严禁改成急切求值**（combo-plan-model/K-002 教训）：覆盖链依赖的
     * [groupSurplusIdleThreshold] / [useIntent] 都带谓词组运行时重算，急切求值 = 每张卡每次构造
     * 就跑全部谓词组，且会拿到构造瞬间尚未就绪的重算结果。
     *
     * `nDelta`（绝望门槛减量）**故意不在此处**——它按血量阶梯变，是运行时减量而非覆盖链的一层，
     * 由消费方在读数后自行相减（[passesSurplusGate]）。
     */
    val idleThreshold: Int by lazy { resolveIdleThreshold() }

    /**
     * 出牌意图：谓词组挂的 OVERRIDE（stageOverride / replanAfterUse / orderWeight）对成员生效。
     *
     * 重算输入 = 静态快照（[CardCombinedConfig.purposeTags] / `purposeReplanAfterUse`）
     * + 完整组集合下的组级 override，配方与启动期 [lin.domain.use.plan.UseIntentAssembler] 一致。
     * `combinedConfig` 可为 null（衍生卡 / 卡池外）——此时用空卡级输入，组级 override 仍应生效。
     */
    val useIntent: UseIntent?
        get() {
            val config = combinedConfig
            if (shouldRecomputeBehavior()) {
                return GroupBehaviorRuntime.resolveUseIntent(
                    allGroupIds,
                    config?.purposeTags ?: emptySet(),
                    config?.purposeReplanAfterUse ?: false
                ) ?: config?.useIntent
            }
            return config?.useIntent
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
    // 【量纲：分】—— A-合流版（D-FO-005 / T-FO-014）后两项**同为分轴**：baseValue 是 costValue(等效费)，
    // extPowerWeight 内含的树分/光环分已由 [lin.domain.context.tacticalContribution] 换算成分
    // （legacy handler 分本就是分）⇒ 本标量是单一量纲，跨轴直加问题已修复。
    val powerWeight: Double
        get() = baseValue + extPowerWeight

    // 运行时加分累加器，初值 = BaseWeight（T-041 归零为 0.0，即无初始常数补贴）。
    // 注意它会进主搜索的 currentWeight，所以初值每 +1 就等于给「每张入选的牌」发 1 分线性补贴
    // ——与 comboPenalty（防堆砌）冲突，详见 EngineConfig.baseWeight 注释。
    // 【量纲：分】写入方 = weightEvaluator 的 [tacticalContribution]（分）+ [auraContribution]（分）
    // + legacy handler 分（历史值，同属分轴）。A-合流版后本字段不再含费值 ⇒ 与 baseValue 同轴直加合法。
    var extPowerWeight: Double = BaseWeight

    // 评估树战术信号（D-007 回归「树分皆战术信号」：全树分 general+tactical，由 weightEvaluator 写入）。
    // 消费方：第一轮候选门控（T-008）、余费门槛绕行、fillValue 溢价（Q-024 费化后即费值，直加）、
    // 排序兜底键（[lin.domain.use.plan.UsePlanOrderer] 的 tactical 档）。
    // Q-008/T-007 的通道分离已无独立消费者（双费数模型取代其使命），通道字段遗留待清理（T-018）。
    // 【量纲：费】T-PV-011 费化后配置侧直接配费值、`tacticalScoreScale` 换算层已退役
    // （见 [lin.domain.context.ComboDefValue]）。语义 =「条件命中 ⇒ 这张牌等效**超模 ts 费**」。
    // A-合流版（D-FO-005 / T-FO-014）后**两层判据不再矛盾**：填充层 `surplusFillValue = E + ts`
    // 与门控/排序照旧按【费】读本字段（序数比较）；只有「进总分竞争」改用换算后的
    // [tacticalContribution]（分）⇒ 跨轴直加问题已修复，本字段保持费值语义不变。
    var tacticalScore: Double = 0.0

    // T-PV-003（play-value-model）：光环广播分（AuraBoost 独立 additive 通道，aura-boost D-004）。
    // 与 [auraContribution] 的分工：本字段是**费**原值（供诊断看配置侧填了多少费），
    // 进总分竞争的是换算后的 [auraContribution]（分）。
    // 【量纲：费】—— 与 tacticalScore 同批「费化」，`aura_boost.score` 是配置侧直接填的费值；
    // A-合流版后不再被当作分直加（见 [auraContribution]）。
    var auraScore: Double = 0.0

    // D-FO-005 A-合流版（T-FO-014）：树分【费】经 costValue 差分换算后的**分**（方案 B 分量落存）。
    // 评估时由 [lin.domain.WeightHandlerDomain.weightEvaluator] 一次换算写入（E 取本卡 equivalentCostValue()）；
    // **主搜索消费它**（[lin.domain.result.DefaultFindBestCombination] 读 extPowerWeight 里的本分量）。
    // 与 [tacticalScore]（费，原值不变）的分工：门控/填充/排序照旧读费值，只有「进总分竞争」用本分量。
    // 换算定义见 [lin.domain.context.tacticalContribution]；ts==0 时为 0.0。
    var tacticalContribution: Double = 0.0

    // D-FO-005 A-合流版（T-FO-014）：光环分【费】同批换算后的**分**（语义与树分同为「超模费」）。
    // 与 [auraScore]（费，原值保留供诊断）并存——诊断看费值，竞争用分量。
    var auraContribution: Double = 0.0

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

    /**
     * 诊断用单行文本。
     *
     * 2026-09-22 修正两处：① 非 UNK 分支**缺闭合 `}`**（日志里整行不可解析）；
     * ② 移除 `useGroupId`/`useGroupOrder` —— 二者是半死字段（见 [useGroupId] 上方 K-003 注记，
     * 无排序/权重读取方），逐卡刷屏只增噪声。**对局日志请改用 [lin.utils.CardLogFormat]**（每卡一行、
     * 定长小数），本方法仅作兜底。
     */
    override fun toString(): String {
        if (card.entityName.startsWith("UNK"))
            return "{id=${cardId()},weight=${powerWeight}}"
        return "{id=${cardId()},name=${card.entityName},weight=${powerWeight}}"
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
