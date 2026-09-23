package lin.domain


import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.data.BaseData
import lin.bean.ComboCard
import lin.bean.passesSurplusCandidate
import lin.config.EngineConfig
import lin.config.cardConfig.CardConfigBind
import lin.domain.result.*
import lin.domain.strategy.FindComboStrategy
import lin.domain.strategy.FindPlanner
import lin.domain.use.UseCardResult
import lin.domain.use.UseDomain
import lin.domain.use.plan.UsePlanBuilder
import lin.domain.use.plan.UsePlanOrderer
import lin.myLog
import lin.utils.CardLogFormat
import lin.warExt.my.base.getCost
import org.koin.core.component.KoinComponent
import org.koin.core.component.get


/**
 * 职责查找combo,使用combo
 */
const val MaxStackNum: Int = 10

/**
 * 一次组合出牌的执行结果（T-038b）：把「批次是否被重规划接管」与「是否需要补偿」两件事**分开表达**。
 *
 * 改造前 [ComboDomain.useCombo] 只返回 Boolean（true = 已 replan），调用方无法区分
 * 「全部打成功」与「有牌打失败但未 replan」——后者该补偿、前者不该，却都走到 `compensateFailedCards`。
 * 结果是补偿路径兼做了「余费填充兜底」，与 D-004 的分工（余费填充归选牌层 `fillSurplusCost`）重叠，
 * 也让「主组合全部打出成功、但仍有余费」时凭空多出一轮补打。
 */
enum class UseComboOutcome {
    /** 组合执行完毕（全部打出且无失败，或组合为空）：无需补偿。 */
    COMPLETED,

    /** 某张牌打出后触发 replan 并已重规划：批次由重规划接管，上层应停止，不补偿。 */
    REPLANNED,

    /** 有牌打失败且未触发 replan：费用可能未用尽，需要补偿。 */
    FAILED,
}

class ComboDomain : KoinComponent {
    private lateinit var warManage: MyWarManage
    private lateinit var weightHandlerDomain: WeightHandlerDomain
    private val usePlanBuilder = get<UsePlanBuilder>()

    private val useDomain = get<UseDomain>()
    private val findComboStrategyList = getKoin().getAll<FindComboStrategy>().sortedBy { it.priority() }
    private val findPlanner = get<FindPlanner>()
    private val classLoaderScope = get<ClassLoaderScope>()
    private val cycleController = get<ComboCycleController>()

    //存储策略分组
    init {
        myLog.info {
            "ComboDao初始化"
        }
        classLoaderScope.withContext {

            //不能移动,需要线程上下文
            warManage = get<MyWarManage>()
            //todo-future 配置绑定暂定放在这,需要warManage之后
            CardConfigBind(warManage.infoMap.mapValues { it.value.weightInfo })

            weightHandlerDomain = get<WeightHandlerDomain>()
        }
    }

    private fun executeEnvironment(runnable: () -> Unit) {
        //重置递归栈状态
        cycleController.reset()
        //生命周期 + 战场环境处理（在线程上下文类加载器下执行）
        classLoaderScope.withContext {
            warManage.executeEnvironment {
                runnable()
            }
        }

    }


    /**
     * 出牌策略
     */
    fun outCardStrategy() {
        myLog.info { "执行出牌策略 (剩余费用:${warManage.getCost()})" }
        executeEnvironment {
            findAndUse()
        }
    }

    /**
     * 该方法会循环调用
     */
    private fun findAndUse() {
        cycleController.transaction {
            var weightPlanner: CmdPlanner = ContinuePlanner
            for (findComboStrategy in findComboStrategyList) {
                weightPlanner = when (weightPlanner) {
                    is ContinuePlanner -> findComboStrategy.find(findPlanner)
                    is ResultPlanner -> findComboStrategy.find(findPlanner, weightPlanner.weightResult)
                        .toPlanner()
                }
            }
            if (weightPlanner is ResultPlanner) {
                when (val weightResult = weightPlanner.weightResult) {
                    is EndWeightResult -> {
                        myLog.info {
                            "选定组合:${weightResult.bestCombination.size} 张\n" +
                                    CardLogFormat.cards(weightResult.bestCombination)
                        }
                        executeUseCard(weightResult)
                    }

                    is EmptyWeightResult -> {
                        log.info { "没有可用卡牌,剩余费用:${warManage.getCost()}" }
                        // T-021b/D-009：技能兜底迁入（原 SkillFindStrategy.emptyResultAction）——
                        // 无候选防空转，「一次机会」防死循环；Banned/负分语义见 MyWarManage.skillFallbackUse
                        warManage.skillFallbackUse()
                    }
                }
            }

        }

    }

    /**
     * 查询和使用的事务（递归栈控制委托给 [ComboCycleController]）
     */



    /**
     *todo
     */
    private fun executeUseCard(weightResult: EndWeightResult) {
        val bestCombination = weightResult.bestCombination
        val lessAble = weightResult.lessAbleUseCards()
        // 语义澄清（T-FO-011）：这里不是"能够使用的卡牌"（那是候选池），而是**落选但仍可用**的次优卡。
        // 原措辞与上一行「选定组合」并列时极易读反，且其内容随逐卡权重全精度刷屏。
        myLog.info { "落选候选(仍可用):${lessAble.size} 张\n${CardLogFormat.cards(lessAble)}" }
        // T-011/Q-009：bestCombination 已含主牌与余费牌（选牌层 fillSurplusCost 合并），
        // useCombo 统一按 UseStage 排序后一次性打出。
        // T-038b：**只有 [UseComboOutcome.FAILED] 才补偿**——余费填充归选牌层 fillSurplusCost（D-004），
        // 补偿不再兼做余费兜底。此前 useCombo 只返回 Boolean（true=已 replan），无法区分
        // 「全部打成功」与「有牌打失败但未 replan」，导致后者该补、前者不该补却都走到补偿。
        if (useCombo(bestCombination) == UseComboOutcome.FAILED) {
            compensateFailedCards(weightResult)
        }
    }

    /**
     * Q-015 失败补偿：主组合中某张牌打失败导致费用未用尽时，用落选卡（[EndWeightResult.lessAbleUseCards]）
     * 在剩余费用内补打。设计要点：
     * - 贪心补打，不用 findStrategy 搜索——剩余可选范围很小（通常 1~3 费），背包/惩罚逻辑是过度设计。
     * - 名字不叫 processLessCost：旧名语义模糊（余费填充 + 失败补偿混在一起），曾导致补偿被误当失败残留删除。
     *   这里只做「失败补偿」一件事，余费填充已由选牌层 fillSurplusCost 承担。
     * - 补打在主组合完整打完后进行，不打断 UseStage 顺序；补打牌再失败会 unUse（isUnUse 硬禁，
     *   Q-036/D-020），被 passesSecondRoundCandidate 挡掉，天然防死循环。
     * - **T-038**：补打批次接入编排四载体（[UsePlanOrderer]），与主组合 [useCombo] 同款排序。
     *   此前仅 `sortedByDescending { powerWeight }`——阶段、段内权重、组间相对顺序、兜底键全被绕开，
     *   导致「配了 CLEAN 先于 DRAW_CARD」「配了 A 组先于 B 组」在补打路径里静默失效。
     *   候选池已由 `passesSurplusCandidate` 挡掉 unUse 牌，故换排序键不会让失败牌插队。
     *
     * // ARCH-UNSETTLED use-intent-model/U-001: 贪心 vs findStrategy 选择未收敛——贪心基于「剩余可选范围很小」的假设，
     * // 若对局出现剩余费用大、落选卡多且需协同的组合（如两个低费牌一起补比单张高费牌更好），需重新评估是否改回
     * // findStrategy 搜索。暂以贪心实现，观察对局后再定。
     */
    private fun compensateFailedCards(weightResult: EndWeightResult) {
        val remaining = warManage.getCost()
        if (remaining <= 0) return
        // D-011：绝望门槛减量现算传入（血量阶梯 → N−Δ，与 fillSurplusCost 同源判定）
        val nDelta = warManage.surplusDespairNDelta()
        val fallbackCards = weightResult.lessAbleUseCards()
            .filter { it.passesSurplusCandidate(remaining, warManage.isFull, nDelta) }
        if (fallbackCards.isEmpty()) return

        // **一次性排序**：基于补打开始时的战场快照（主组合的 tryUseCard 已使上一份 ruleEnv 失效，
        // 此处取到的是重建后的当前快照），与主组合「先排后打」语义一致。
        // 不逐张重排——否则 relation 约束会在批次内随战场漂移，顺序更难推理，也失去与主组合的对称性。
        val ordered = UsePlanOrderer.order(usePlanBuilder.build(fallbackCards, warManage.ruleEnv))
        for (card in ordered) {
            // 实时校验：上一步补打可能已消耗费用/改变战场
            if (card.cost() > warManage.getCost()) continue
            // replan 已在 useCardAndIsReload 内部触发 findAndUse
            if (useCardAndIsReload(card).shouldReplan) return
        }
    }

    /**
     * 打出一组已选定的牌（[UsePlanOrderer] 排序后逐张打出）。
     *
     * 返回值把两件事分开表达（[UseComboOutcome]）：「批次是否被重规划接管」与「是否要补偿」。
     * 改造前只返回 Boolean（true=已 replan），调用方无法区分「全部成功」与「有失败但没 replan」。
     */
    fun useCombo(bestCombination: List<ComboCard>): UseComboOutcome {
        // 空组合 = 选牌层已把候选全部挡下（余费门槛/Banned）。补偿池与 fillSurplusCost 同源、
        // 过同一套门槛判定，再判一次是无效重复；余费填充归选牌层（D-004），此处不兜底。
        if (bestCombination.isEmpty()) return UseComboOutcome.COMPLETED

        // 单张无需排序——阶段比较与拓扑约束对一张牌无意义（省掉 build+order 开销）
        if (bestCombination.size == 1) {
            return outcomeOf(useCardAndIsReload(bestCombination.first()))
        }

        val needCost = bestCombination.sumOf { it.cost() }

        // 复用评估阶段那一批次快照（warManage.ruleEnv，D-022）：评估→排序之间无出牌动作，战场口径一致，
        // 且 crossCard 分段管道缓存得以共享（原先各自 new 一个 WarInfoEnv）。
        // 排序语义由 [UsePlanOrderer] 单点承载（D-015/D-021）——T-038 之前补打批次另起了一个
        // `sortedByDescending { powerWeight }`，四载体全被绕开，勿再引入第二个排序点。
        val bestCombinationCombo =
            UsePlanOrderer.order(usePlanBuilder.build(bestCombination, warManage.ruleEnv))

        myLog.info {
            val finalWeight = bestCombinationCombo.sumOf { it.powerWeight }
            "出牌组合: 总费=$needCost 总分=${CardLogFormat.fixed(finalWeight)}\n" +
                    CardLogFormat.cards(bestCombinationCombo)
        }

        var anyFailed = false
        for (card in bestCombinationCombo) {
            val result = useCardAndIsReload(card)
            // replan 已在 useCardAndIsReload 内部触发 findAndUse——批次由重规划接管，
            // 后续牌已不在本批次语义内，立即返回且不补偿。
            if (result.shouldReplan) return UseComboOutcome.REPLANNED
            // 打失败不中断：继续打后面的牌（费用与战场已变，由循环内的 useCardAndIsReload 逐个判定）
            if (!result.succeeded) anyFailed = true
        }
        return if (anyFailed) UseComboOutcome.FAILED else UseComboOutcome.COMPLETED
    }

    /**
     * 单张牌的结果映射。
     *
     * **replan 优先于失败**：批次已由重规划接管，此时再补偿会与重规划后的新批次打架
     * （补打的牌可能已被新批次选中）。
     */
    private fun outcomeOf(result: UseCardResult): UseComboOutcome = when {
        result.shouldReplan -> UseComboOutcome.REPLANNED
        !result.succeeded -> UseComboOutcome.FAILED
        else -> UseComboOutcome.COMPLETED
    }


    /**
     * 打出一张牌，并在状态变化时重规划。
     *
     * @return 打出结果（含 `succeeded` 与 `shouldReplan` 两个独立信号）
     *   ——T-038b 起返回完整 [UseCardResult] 而非布尔量：调用方需要区分
     *   「打失败」与「触发 replan」，二者对后续动作（是否补偿/是否继续）含义不同。
     */
    fun useCardAndIsReload(card: ComboCard): UseCardResult {
        val result = useDomain.useCard(card)
        // D-005：打失败（succeeded=false）不再强制 reLoad。打失败时 isChangeByUseSuccess 收到 null 返回 false
        // → stateChanged/shouldReplan 均为 false；若用 `!succeeded` 强制 reLoad，会经 reLoadHandCards 重建 ComboCard
        // 丢失 unUse 状态，打不出的牌反复复活重试 → 死循环。打失败的牌已在 tryUseCard 尾部 unUse()，
        // 本周期内不再进候选；只有真正状态变化（打出成功/抽牌等）才需要 reLoad 重规划。
        if (result.shouldReplan) {
            warManage.reLoad()
            findAndUse()
        }
        return result
    }



    fun executeChangeCard(cards: HashSet<Card>) {
        classLoaderScope.withContext {
            if (BaseData.enableChangeWeight) {
                val changeWeightResult = ChangeWeightResult(cards, warManage.parseComboCards(cards.toList()))
                changeWeightResult.processChangeCard()
            } else {
                // T-FO-011：本分支原先**零日志**（实战复盘「换牌一条记录都没有」的一半原因）。
                myLog.info { "起手换牌: 宿主未启用换牌权重，按费用兜底全换 cost > ${EngineConfig.changeKeepCost}" }
                cards.removeIf { card -> card.cost > EngineConfig.changeKeepCost }
            }
        }
    }

    fun executeDiscoverChooseCard(vararg cards: Card): Int {
        var index = 0
        try {
            classLoaderScope.withContext {
                index = weightHandlerDomain.executeDiscoverChooseCard(*cards)
            }
            return index
        } finally {
            useDomain.onSdkChooseCompleted()
        }
    }

}




