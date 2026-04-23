package lin.rule.handler

import lin.bean.ComboCard
import lin.bean.cardExt.base.intentRuleMap
import lin.config.ConfigDispatcher
import lin.config.RuleMap
import lin.config.processMoreConfig
import lin.domain.MyWarManage
import lin.rule.RuleInfoRegister
import lin.rule.context.RuleContext
import lin.rule.tree.ConditionInstanceNode
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
            for ((_, levelRules) in intentMap) {
                val successes = levelRules.mapNotNull { rule ->
                    val res = rule.intentCmd(callCard, warManage)
                    if (res is RuleResult.Continue) res else null
                }

                if (successes.isNotEmpty()) {
                    successes.forEach {
                        result += it.score
                        it.modifyCard?.let { action -> callCard.update(action) }
                    }
                    // TODO: 旧机制中如果有某种 "同一Level内命中后屏蔽低Level" 处理，可在调整后补充
                }
            }
        }
        return result
    }

    /**
     * 【新架构】面向 AST 条件树的组合求值与意图收集器
     */
    fun evaluateConditionTree(
        node: ConditionInstanceNode,
        context: RuleContext,
        collectedActions: MutableList<ComboCardAction>
    ): RuleResult {
        return when (node) {
            is ConditionInstanceNode.RuleNode -> {
                val res = node.ruleLogic(context)
                if (res is RuleResult.Continue && res.modifyCard != null) {
                    collectedActions.add(res.modifyCard)
                }
                res
            }

            is ConditionInstanceNode.AndNode -> {
                var totalScore = 0.0
                var allFeasible = true
                for (child in node.children) {
                    val res = evaluateConditionTree(child, context, collectedActions)
                    if (res is RuleResult.Prune) return RuleResult.Prune
                    if (res is RuleResult.Continue) {
                        totalScore += res.score
                        if (!res.feasible) allFeasible = false
                    }
                }
                RuleResult.Continue(feasible = allFeasible, score = totalScore)
            }

            is ConditionInstanceNode.OrNode -> {
                // OrNode 短路：只取第一个 feasible 为 true 的分支
                for (child in node.children) {
                    val localActions = mutableListOf<ComboCardAction>()
                    val res = evaluateConditionTree(child, context, localActions)
                    if (res is RuleResult.Continue && res.feasible) {
                        collectedActions.addAll(localActions)
                        return res
                    }
                }
                RuleResult.Prune
            }

            is ConditionInstanceNode.NotNode -> {
                val localActions = mutableListOf<ComboCardAction>()
                val res = evaluateConditionTree(node.child, context, localActions)
                if (res is RuleResult.Prune || (res is RuleResult.Continue && !res.feasible)) {
                    RuleResult.Continue(feasible = true, score = 0.0)
                } else {
                    RuleResult.Prune
                }
            }

            is ConditionInstanceNode.BranchNode -> {
                val conditionRes = node.condition(context)
                if (conditionRes is RuleResult.Continue && conditionRes.feasible) {
                    evaluateConditionTree(node.onTrue, context, collectedActions)
                } else {
                    evaluateConditionTree(node.onFalse, context, collectedActions)
                }
            }
        }
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