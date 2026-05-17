package lin.rule.handler

import lin.bean.ComboCard
import lin.bean.cardExt.base.intentEvaluatorRoots
import lin.config.ConfigDispatcher
import lin.config.EvaluatorTreeRoot
import lin.domain.MyWarManage
import lin.rule.context.RuleContext
import lin.rule.context.RuleEnv
import lin.rule.context.WarInfoEnv
import lin.rule.registry.RuleRegistry
import lin.rule.tree.BindingGroupId
import lin.rule.tree.EvaluatorInstanceNode
import lin.rule.tree.TreeConfigProvider
import lin.rule.tree.instantiate
import lin.weightHandler.WeightHandler
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

class IntentRuleHandler : KoinComponent, WeightHandler {

    init {
        bindRule()
    }

    private fun bindRule() {
        val ruleRegistry = get<RuleRegistry>()
        val configDispatcher = get<ConfigDispatcher>()
        val providers = get<List<TreeConfigProvider>>()
        for (provider in providers) {
            for (config in provider.findAll()) {
                val instance = config.instantiate(ruleRegistry::build)
                configDispatcher.processByType(
                    instance.bindGroupIds.map { BindingGroupId(it) },
                    listOf(EvaluatorTreeRoot(instance.root))
                )
            }
        }
    }

    override fun cardWeightCompute(callCard: ComboCard, warManage: MyWarManage): Double {
        var result = 0.0
        callCard.intentEvaluatorRoots()?.let { roots ->
            val context = RuleContext(callCard, warManage)
            val collectedActions = mutableListOf<ComboCardAction>()
            //todo 需要把方法提级,不是每个card都创建一个
            with(WarInfoEnv(warManage)) {
                for (root in roots) {
                    val res = evaluateConditionTree(root, context, collectedActions)
                    if (res is RuleResult.Continue) {
                        result += res.score
                    }
                }
            }
            collectedActions.forEach { callCard.update(it) }
        }
        return result
    }

    /**
     * 面向 AST 条件树的组合求值与意图收集器
     */
    context(ruleEnv: RuleEnv)
    fun evaluateConditionTree(
        node: EvaluatorInstanceNode,
        context: RuleContext,
        collectedActions: MutableList<ComboCardAction>
    ): RuleResult {
        return when (node) {
            is EvaluatorInstanceNode.RuleNode -> {
                val res = node.ruleLogic(context)
                if (res is RuleResult.Continue && res.modifyCard != null) {
                    collectedActions.add(res.modifyCard)
                }
                res
            }

            is EvaluatorInstanceNode.AndNode -> {
                var totalScore = 0.0
                for (child in node.children) {
                    val res = evaluateConditionTree(child, context, collectedActions)
                    if (res is RuleResult.Prune) return RuleResult.Prune
                    if (res is RuleResult.Continue) {
                        totalScore += res.score
                    }
                }
                RuleResult.Continue(score = totalScore)
            }

            is EvaluatorInstanceNode.OrNode -> {
                for (child in node.children) {
                    val localActions = mutableListOf<ComboCardAction>()
                    val res = evaluateConditionTree(child, context, localActions)
                    if (res is RuleResult.Continue) {
                        collectedActions.addAll(localActions)
                        return res
                    }
                }
                RuleResult.Prune
            }

            is EvaluatorInstanceNode.NotNode -> {
                val localActions = mutableListOf<ComboCardAction>()
                val res = evaluateConditionTree(node.child, context, localActions)
                if (res is RuleResult.Prune) {
                    RuleResult.Continue(score = 0.0)
                } else {
                    RuleResult.Prune
                }
            }

            is EvaluatorInstanceNode.BranchNode -> {
                val conditionRes = node.condition(context)
                if (conditionRes is RuleResult.Continue) {
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
 */
private fun ComboCard.update(intent: ComboCardAction): ComboCard {
    intent.useAfterStrategies?.let {
        this.useAfterStrategy?.addAll(it) ?: { this.useAfterStrategy = it.toMutableList() }
    }

    intent.useBeforeStrategies?.let {
        this.useBeforeStrategy?.addAll(it) ?: { this.useBeforeStrategy = it.toMutableList() }
    }

    intent.useGroupId?.let {
        this.useGroupId = it
    }

    intent.useGroupOrder?.let {
        this.useGroupOrder = it
    }

    if (intent.pointCard != null) {
        this.pointCard = intent.pointCard
    }

    return this
}
