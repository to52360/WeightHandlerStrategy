package lin.domain


import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.data.BaseData
import lin.bean.ComboCard
import lin.bean.cardExt.base.isMinion
import lin.config.cardConfig.CardConfigBind
import lin.domain.context.CostWeight
import lin.domain.context.NotWeight
import lin.domain.result.*
import lin.domain.strategy.FindComboStrategy
import lin.domain.strategy.FindPlanner
import lin.domain.use.UseDomain
import lin.domain.use.order.UseOrderPlanner
import lin.domain.use.plan.UsePlanBuilder
import lin.domain.use.plan.UsePlanOrderer
import lin.myLog
import lin.serviceLoader.findCombo.SkillFindStrategy
import lin.warExt.my.base.getCost
import org.koin.core.component.KoinComponent
import org.koin.core.component.get


/**
 * 职责查找combo,使用combo
 */
const val MaxStackNum: Int = 10

class ComboDomain : KoinComponent {
    companion object {
        val USE_ORDER: Comparator<ComboCard> = UseOrderPlanner.BASE_ORDER
    }

    private lateinit var warManage: MyWarManage
    private lateinit var weightHandlerDomain: WeightHandlerDomain
    private val usePlanBuilder = get<UsePlanBuilder>()

    private val useDomain = get<UseDomain>()
    private val findComboStrategyList = getKoin().getAll<FindComboStrategy>().sortedBy { it.priority() }
    private val findPlanner = get<FindPlanner>()
    private val skillFindStrategy = get<SkillFindStrategy>()
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
     * 处理剩余费用
     */
    private fun processLessCost(weightResult: EndWeightResult): Boolean {

        val unAbleUseCards = weightResult.lessAbleUseCards()
        myLog.info { "剩余未卡牌:${unAbleUseCards}" }
        val costWeight = CostWeight * warManage.getCost()

        //没有为0的牌
        if (costWeight == NotWeight && !unAbleUseCards.any { it.cost() == 0 }) return false
        val isFull = warManage.isFull
        //todo 注意使用useGroupOrder排除费用权重的影响,还调整了满了,随从
        val moreTryCard =
            unAbleUseCards.filter { it.cost() <= warManage.getCost() && costWeight + it.useGroupOrder > NotWeight && !(isFull && it.isMinion()) }
        if (moreTryCard.isEmpty()) return false

        val bestCombos = UsePlanOrderer.order(
            usePlanBuilder.build(
            DefaultFindBestCombination.findBestCombination(moreTryCard, warManage.getCost())
            )
        )
        //todo 还会存在打不出的情况
        for (bestCombo in bestCombos) {
            if (useCardAndIsReload(bestCombo)) return true
        }

        return false
    }

    /**
     * 出牌策略
     */
    fun outCardStrategy() {
        myLog.info { "执行出牌策略" }
        executeEnvironment {
            findAndUse()
            //todo 临时不使用技能解决方案
            skillFindStrategy.useSkill(warManage)
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
        val extLessCost = warManage.getCost() - weightResult.costSum()
        val result = useCombo(bestCombination)
        //重新执行
        if (result) return

        val realLessCost = warManage.getCost()
        if (realLessCost > extLessCost) //说明有些牌没打出去,进行补偿
            processLessCost(weightResult)

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
            UsePlanOrderer.order(usePlanBuilder.build(bestCombination))

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
        val shouldReplan = !result.succeeded || result.shouldReplan
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




