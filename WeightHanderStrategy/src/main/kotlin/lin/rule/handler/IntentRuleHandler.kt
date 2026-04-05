package lin.rule.handler

import lin.bean.ComboCard
import lin.bean.cardExt.base.intentRuleMap
import lin.config.ConfigDispatcher
import lin.config.RuleMap
import lin.config.processMoreConfig
import lin.domain.MyWarManage
import lin.domain.context.UnUseWeight
import lin.rule.RuleInfoRegister
import lin.serviceLoader.weightRule.IntentRuleInfo
import lin.weightHandler.WeightHandler
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/**
 * 待定,方案有问题,需要调整,只用于参考
 */
class IntentRuleHandler : KoinComponent, WeightHandler {

    init {
        bindRule()
    }

    private fun bindRule() {
        val ruleInfoRegister = get<RuleInfoRegister>()
        val ruleMap = ruleInfoRegister.getRules<IntentRuleInfo>().mapValues { (_, intentRuleInfo) ->
            intentRuleInfo.groupBy { it.ruleLevel }
                .toSortedMap(compareByDescending { it.value })
        }
        val configDispatcher = get<ConfigDispatcher>()
        ruleMap.forEach { (key, value) ->
            configDispatcher.processMoreConfig(key, RuleMap(value))
        }

    }

    override fun cardWeightCompute(callCard: ComboCard, warManage: MyWarManage): Double {
        var result = 0.0
        callCard.intentRuleMap()?.let { intentMap ->
            //高等级启用信号,低等级停止信号变为跳过信号
            var notHasEnable = true
            for ((_, levelRules) in intentMap) {
                val results = levelRules.mapNotNull { rule ->
                    val res = rule.intentCmd(callCard, warManage)
                    if (res !is SkipResult) res else null
                }
                val successes = results.filterIsInstance<EnableResult>()
                val stops = results.filterIsInstance<StopResult>()
                when {
                    successes.isNotEmpty() -> {
                        // 启用信号：累加权重，更新卡片
                        notHasEnable = false
                        successes.forEach {
                            result += it.weight
                            //todo-future 暂时这样,先转移操作权,后续再做打算
                            it.modifyCard?.let { callCard.update(it) }
                        }
                    }

                    stops.isNotEmpty() -> {
                        // 同等级无成功但有 Stop：立即终止
                        if (notHasEnable) //有成功就跳过
                            return UnUseWeight
                    }

                }
            }

        }
        return result

    }


}

/**
 * ComboCard 的扩展函数，用于应用意图配置。
 *
 * @param intent 包含要更新的新值的配置对象。
 * @return 返回更新后的 ComboCard 实例 (即 this)。
 */
private fun ComboCard.update(intent: ComboCardAction): ComboCard {
    // 1. 更新 List 策略字段
    // 如果 intent.useAfterStrategies 不为 null，则更新 ComboCard 中的 MutableList
    intent.useAfterStrategies?.let {
        this.useAfterStrategy?.addAll(it) ?: { this.useAfterStrategy = it.toMutableList() }
    }

    intent.useBeforeStrategies?.let {
        this.useBeforeStrategy?.addAll(it) ?: { this.useBeforeStrategy = it.toMutableList() }

    }

    // 2. 更新基本类型字段
    // 如果 intent.useGroupId 不为 null，则更新 ComboCard 字段
    intent.useGroupId?.let {
        this.useGroupId = it
    }

    intent.useGroupOrder?.let {
        this.useGroupOrder = it
    }

    // 3. 更新 Card 对象字段
    // 即使 pointCard 为 null，也可能意味着用户希望清除旧的 Card 对象，
    // 所以这里的处理要根据业务逻辑来决定。
    // 如果意图的 pointCard 为 null，表示不更新；如果不为 null，则更新。
    // 如果业务允许 pointCard 设置为 null 来清除，则使用简单的赋值。
    // 这里我们假设 intent.pointCard != null 时才更新
    if (intent.pointCard != null) {
        this.pointCard = intent.pointCard
    }

    // 或者，如果你确定意图中的 null 意味着清除：
    // this.pointCard = intent.pointCard // 这种写法更简洁

    return this
}