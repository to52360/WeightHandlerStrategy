package lin.rule.handler

import lin.bean.ComboCard
import lin.bean.cardExt.base.intentEvaluatorRoots
import lin.config.ConfigDispatcher
import lin.config.EvaluatorTreeRoot
import lin.domain.MyWarManage
import lin.rule.context.RuleContext
import lin.rule.context.RuleEnv
import lin.rule.tree.*
import lin.serviceLoader.provider.TreeConfigProvider
import lin.utils.startup.StartupTask
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/**
 * 启动任务：将 TreeConfigProvider 中的评估树绑定到 ConfigDispatcher。
 */
class RuleTreeBindingTask : StartupTask, KoinComponent {

    override fun execute() {
        val configDispatcher = get<ConfigDispatcher>()
        val logicAssembler = get<LeafLogicAssembler>()
        val providers = getKoin().getAll<TreeConfigProvider>()

        for (provider in providers) {
            for (config in provider.findAll()) {
                val instance = config.instantiate(
                    leafBuilder = logicAssembler::build,
                    branchConditionBuilder = logicAssembler::buildBranch
                )

                // 按 instance.bindingType 分发给对应的 Finder
                when (instance.bindingType) {
                    EvaluatorTreeBindingType.GROUP -> {
                        configDispatcher.processByType(
                            instance.bindingIds.map { BindingGroupId(it) },
                            listOf(EvaluatorTreeRoot(instance.root))
                        )
                    }

                    EvaluatorTreeBindingType.PURPOSE_TAG -> {
                        configDispatcher.processByType(
                            instance.bindingIds.map { PurposeTagBindingId(it) },
                            listOf(EvaluatorTreeRoot(instance.root))
                        )
                    }
                }
            }
        }
    }
}


// ────────────────────────────────────────────────────────────
// 顶层函数：供编排函数调用
// ────────────────────────────────────────────────────────────

/**
 * 对一张卡牌的所有意图评估根节点执行条件树求值。
 * 需要在 [RuleEnv] 作用域内调用（例如 `with(WarInfoEnv(warManage))`）。
 */
context(ruleEnv: RuleEnv)
fun evaluateCardRoots(
    card: ComboCard,
    warManage: MyWarManage,
): RuleResult.Accumulate {
    var totalScore = 0.0
    val collectedActions = mutableListOf<ComboCardAction>()

    card.intentEvaluatorRoots()?.let { roots ->
        val context = RuleContext(card, warManage)
        for (root in roots) {
            val res = evaluateConditionTree(root, context, collectedActions)
            if (res is EvalOutcome.Pruned) return RuleResult.Accumulate(totalScore, collectedActions, pruned = true)
            when (res) {
                is EvalOutcome.Matched -> totalScore += res.score
                is EvalOutcome.Skipped -> totalScore += res.score
                EvalOutcome.Pruned -> {} // 不可达，前面已 return
            }
        }
    }

    return RuleResult.Accumulate(totalScore, collectedActions, pruned = false)
}

/**
 * 面向 AST 条件树的组合求值与意图收集器
 */
context(ruleEnv: RuleEnv)
fun evaluateConditionTree(
    node: EvaluatorInstanceNode,
    context: RuleContext,
    collectedActions: MutableList<ComboCardAction>
): EvalOutcome {
    return when (node) {
        is EvaluatorInstanceNode.RuleNode -> {
            val outcome = node.leafLogic(context)
            if (outcome is EvalOutcome.Matched && outcome.modifyCard != null) {
                collectedActions.add(outcome.modifyCard)
            }
            outcome
        }

        is EvaluatorInstanceNode.AndNode -> {
            var totalScore = 0.0
            var anyMatched = false
            for (child in node.children) {
                val res = evaluateConditionTree(child, context, collectedActions)
                if (res is EvalOutcome.Pruned) return EvalOutcome.Pruned
                when (res) {
                    is EvalOutcome.Matched -> {
                        anyMatched = true
                        totalScore += res.score
                    }

                    is EvalOutcome.Skipped -> totalScore += res.score
                    is EvalOutcome.Pruned -> {}
                }
            }
            if (anyMatched) EvalOutcome.Matched(totalScore) else EvalOutcome.Skipped(totalScore)
        }

        is EvaluatorInstanceNode.OrNode -> {
            for (child in node.children) {
                val localActions = mutableListOf<ComboCardAction>()
                val res = evaluateConditionTree(child, context, localActions)
                if (res is EvalOutcome.Matched) {
                    collectedActions.addAll(localActions)
                    return res
                }
                if (res is EvalOutcome.Pruned) return EvalOutcome.Pruned
                // Skipped: 继续尝试下一个子节点
            }
            EvalOutcome.Skipped(score = 0.0)
        }

        is EvaluatorInstanceNode.NotNode -> {
            val localActions = mutableListOf<ComboCardAction>()
            val res = evaluateConditionTree(node.child, context, localActions)
            when (res) {
                is EvalOutcome.Matched -> EvalOutcome.Skipped(score = 0.0)
                is EvalOutcome.Skipped -> EvalOutcome.Matched(score = 0.0)
                is EvalOutcome.Pruned -> EvalOutcome.Pruned
            }
        }

        is EvaluatorInstanceNode.BranchNode -> {
            if (node.condition(context)) {
                evaluateConditionTree(node.onTrue, context, collectedActions)
            } else {
                evaluateConditionTree(node.onFalse, context, collectedActions)
            }
        }
    }
}

/**
 * ComboCard 的扩展函数，用于应用意图配置。
 */
fun ComboCard.updateIntent(actions: List<ComboCardAction>) {
    for (intent in actions) {
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
    }
}



