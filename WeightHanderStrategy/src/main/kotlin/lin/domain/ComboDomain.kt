package lin.domain


import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.data.BaseData
import lin.bean.ComboCard
import lin.bean.passesSurplusCandidate
import lin.config.cardConfig.CardConfigBind
import lin.domain.result.*
import lin.domain.strategy.FindComboStrategy
import lin.domain.strategy.FindPlanner
import lin.domain.use.UseDomain
import lin.domain.use.order.UseOrderPlanner
import lin.domain.use.plan.UsePlanBuilder
import lin.domain.use.plan.UsePlanOrderer
import lin.myLog
import lin.rule.context.WarInfoEnv
import lin.warExt.my.base.getCost
import org.koin.core.component.KoinComponent
import org.koin.core.component.get


/**
 * 职责查找combo,使用combo
 */
const val MaxStackNum: Int = 10

class ComboDomain : KoinComponent {
    //todo 重构之后,新的排序怎么处理
    companion object {
        val USE_ORDER: Comparator<ComboCard> = UseOrderPlanner.BASE_ORDER
    }

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
        myLog.info { "执行出牌策略" }
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
                        myLog.info { "找到需要使用的卡牌:${weightResult.bestCombination}" }
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
        myLog.info { "能够使用的卡牌:${weightResult.lessAbleUseCards()}" }
        // T-011/Q-009：bestCombination 已含主牌与余费牌（选牌层 fillSurplusCost 合并），
        // useCombo 统一按 UseStage 排序后一次性打出；打失败/replan 由 useCardAndIsReload 兜底重规划。
        if (useCombo(bestCombination)) return
        // Q-015：主组合有牌打失败（succeeded=false）时费用可能未用尽，用落选卡在剩余费用内补打。
        compensateFailedCards(weightResult)
    }

    /**
     * Q-015 失败补偿：主组合中某张牌打失败导致费用未用尽时，用落选卡（[EndWeightResult.lessAbleUseCards]）
     * 在剩余费用内补打。设计要点：
     * - 贪心补打，不用 findStrategy 搜索——剩余可选范围很小（通常 1~3 费），背包/惩罚逻辑是过度设计。
     * - 名字不叫 processLessCost：旧名语义模糊（余费填充 + 失败补偿混在一起），曾导致补偿被误当失败残留删除。
     *   这里只做「失败补偿」一件事，余费填充已由选牌层 fillSurplusCost 承担。
     * - 补打在主组合完整打完后进行，不打断 UseStage 顺序；补打牌再失败会 unUse（powerWeight < 0），
     *   被 passesSecondRoundCandidate 挡掉，天然防死循环。
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
            .sortedByDescending { it.powerWeight }
        for (card in fallbackCards) {
            // 实时校验：上一步补打可能已消耗费用/改变战场
            if (card.cost() > warManage.getCost()) continue
            if (useCardAndIsReload(card)) return // replan 已在 useCardAndIsReload 内部触发 findAndUse
        }
    }

    /**
     * @return false 为执行完毕,true为重新调用
     */
    fun useCombo(bestCombination: List<ComboCard>): Boolean {

        if (bestCombination.isEmpty()) return false
        // 5. 执行找到的最佳出牌组合
        //只有一个处理
        if (bestCombination.size == 1) {
            useCardAndIsReload(bestCombination.first())
            return false
        }


        val needCost = bestCombination.sumOf { it.cost() }

        /**
         * todo-future 这个排序有重,可以根据不同上下切换
         * 上下文判断入口
         * [MyWarManage.parseCombo]
         */
        val bestCombinationCombo =
            //todo-future 需要额外创建WarInfoEnv,评估已经有一个
            UsePlanOrderer.order(usePlanBuilder.build(bestCombination, WarInfoEnv(warManage)))

        myLog.info {
            val finalWeight = bestCombinationCombo.sumOf { it.powerWeight }
            val msg =
                "找到最优出牌组合 (总费用: $needCost, 总权重: $finalWeight): $bestCombinationCombo"
            msg
        }

        for (card in bestCombinationCombo) {
            if (useCardAndIsReload(card)) return true
        }
        return false
    }


    /**
     * 权重有变化重新匹配
     */
    fun useCardAndIsReload(card: ComboCard): Boolean {
        val result = useDomain.useCard(card)
        // D-005：打失败（succeeded=false）不再强制 reLoad。打失败时 isChangeByUseSuccess 收到 null 返回 false
        // → stateChanged/shouldReplan 均为 false；若用 `!succeeded` 强制 reLoad，会经 reLoadHandCards 重建 ComboCard
        // 丢失 unUse 状态，打不出的牌反复复活重试 → 死循环。打失败的牌已在 tryUseCard 尾部 unUse()，
        // 本周期内不再进候选；只有真正状态变化（打出成功/抽牌等）才需要 reLoad 重规划。
        val shouldReplan = result.shouldReplan
        if (shouldReplan) {
            warManage.reLoad()
            findAndUse()
        }
        return shouldReplan
    }



    fun executeChangeCard(cards: HashSet<Card>) {
        classLoaderScope.withContext {
            if (BaseData.enableChangeWeight) {
                val changeWeightResult = ChangeWeightResult(cards, warManage.parseComboCards(cards.toList()))
                changeWeightResult.processChangeCard()

            } else {
                cards.removeIf { card -> card.cost > 2 }
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




