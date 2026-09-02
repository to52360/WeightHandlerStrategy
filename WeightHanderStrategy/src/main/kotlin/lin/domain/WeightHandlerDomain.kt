package lin.domain

import club.xiaojiawei.hsscriptcardsdk.bean.Card
import lin.bean.ComboCard
import lin.domain.context.NotWeight
import lin.domain.context.UnUseWeight
import lin.domain.result.EmptyWeightResult
import lin.domain.result.EndWeightResult
import lin.domain.result.WeightResult
import lin.myLog
import lin.rule.context.RuleEnv
import lin.rule.handler.AuraBoostEvaluator
import lin.rule.handler.EvalSignal
import lin.rule.handler.evaluateCardRoots
import lin.rule.handler.updateIntent
import lin.utils.serviceLoader.ServiceLoaderUtils
import lin.warExt.my.base.getCost
import lin.weightHandler.DiscoverWeightHandler
import lin.weightHandler.InitHandler
import lin.weightHandler.WeightHandler
import org.koin.core.component.KoinComponent


class WeightHandlerDomain(val warManage: MyWarManage) : KoinComponent {
    private val weightHandlers: MutableList<WeightHandler> = mutableListOf()
    private val discoverWeightHandlers: MutableList<DiscoverWeightHandler> = mutableListOf()
    private val auraBoostEvaluator: AuraBoostEvaluator by lazy { getKoin().get() }

    init {
        try {
            val infos = warManage.infoMap
            val cardWeightInfos = infos.values.map { it.weightInfo }
            val services = ServiceLoaderUtils.loadServicesByMutable(WeightHandler::class.java, weightHandlers)

            services.sortBy {
                //按ai的说法会语义多重,实践看看有什么后果
                if(it is InitHandler) {
                    it.init(cardWeightInfos)
                }


                warManage.registerLifecycle(it)


                //todo 存在一个问题没法单独扩展发现策略
                if (it is DiscoverWeightHandler) {
                    discoverWeightHandlers.add(it)
                }
                it.priority()
            }


        } catch (e: Exception) {
            e.printStackTrace()
            myLog.error(e) { "测试化失败" }
            throw e
        }


    }

    /**
     * 调用权重规则
     */
    private fun processWeight(weightResult: EndWeightResult) {
        // 决策批次共享快照（MyWarManage.ruleEnv）：评估与紧随的排序之间无出牌动作，口径一致。
        val ruleEnv = warManage.ruleEnv
        weightResult.canUseCards.forEach { comboCard ->
            val calWeight = weightEvaluator(comboCard, warManage, ruleEnv)
            if (calWeight != NotWeight) {
                if (calWeight == UnUseWeight) {
                    comboCard.unUse()
                } else {
                    comboCard.addWeight(calWeight)
                }
            }
            weightResult.processWeightAfter(comboCard)
        }
    }

    /**
     * 单入口编排函数：先条件树求值，后 legacy handler 链。
     */
    private fun weightEvaluator(
        comboCard: ComboCard,
        warManage: MyWarManage,
        ruleEnv: RuleEnv,
    ): Double {
        var total = 0.0

        // 1. 条件树求值（新系统）
        // @verify purpose-tag-extension/U-002: EvalSignal.Banned 异常穿透，隐式控制流；唯一捕获边界在编排层
        val treeResult = try {
            evaluateCardRoots(comboCard, warManage, ruleEnv)
        } catch (e: EvalSignal) {
            when (e) {
                EvalSignal.Banned -> {
                    comboCard.unUse()
                    return UnUseWeight
                }
            }
        }
        if (treeResult.actions.isNotEmpty()) {
            comboCard.updateIntent(treeResult.actions)
        }
        // D-007 回归「树分皆战术信号」：全树分进出牌总权重，同时整体作为战术信号存 ComboCard
        //（第一轮门控 / 余费门槛绕行 / fillValue 溢价消费）。Q-008 通道分离遗留待清理（T-018）。
        total += treeResult.score
        comboCard.tacticalScore = treeResult.score

        // 1.5 push 广播分（独立 additive 通道，aura-boost D-004；光环加分只走 AuraBoost，评估树不写光环条件）
        total += auraBoostEvaluator.activeScore(comboCard, ruleEnv)

        // 2. legacy handler 链（旧系统，兼容）
        for (handler in weightHandlers) {
            val w = handler.cardWeightCompute(comboCard, warManage)
            if (w == UnUseWeight) {
                return UnUseWeight
            }
            if (w != NotWeight) {
                total += w
            }
        }

        return total
    }

    /**
     * 查找组合
     * 存在循环调用
     */
    fun findCombination(
        cost: Int = warManage.getCost(),
        canUseCardsByCost: List<ComboCard> = warManage.canUseCards
    ): WeightResult {
        myLog.info { "执行查组组合" }
        if (canUseCardsByCost.isEmpty()) return EmptyWeightResult
        val weightResult = EndWeightResult(canUseCardsByCost, cost)
        processWeight(weightResult)
        if (weightResult.notAbleUseCards()) return weightResult

        findBestCombination(weightResult)
        return weightResult
    }

    /**
     * 查找最好的组合
     */
    fun findBestCombination(weightResult: EndWeightResult): EndWeightResult {
        // T-011：传入战场是否已满，供余费填充排除满场随从（与执行层 UseFunction 硬拦截语义一致）。
        // D-011：全局绝望门槛减量现算传入（血量阶梯 → N−Δ，一次一判无缓存）。
        weightResult.findBestCombination(warManage.isFull, warManage.surplusDespairNDelta())
        return weightResult
    }




    /**
     * 发现策略
     */
    fun executeDiscoverChooseCard(vararg cards: Card): Int{
        var maxIndex = 0
        var maxWeight = NotWeight
        for(i in cards.indices){
            val comboCard = warManage.parseComboCard(cards[i])
            // 基础价值（配置费用/身材/法术费用三分流）已在 parseComboCard 注入 baseValue
            var finalWeight = comboCard.baseValue
            discoverWeightHandlers.forEach {
                finalWeight += it.cardWeight(comboCard)
            }
            if (finalWeight > maxWeight) {
                maxWeight = finalWeight
                maxIndex = i
            }
        }
        val maxWeightCard = cards[maxIndex]
        myLog.info { "发现权最大值:id:${maxWeightCard.cardId},名字:${maxWeightCard.entityName}的发现权重:$maxWeight,选择下标:$maxIndex" }

        return maxIndex

    }





}
