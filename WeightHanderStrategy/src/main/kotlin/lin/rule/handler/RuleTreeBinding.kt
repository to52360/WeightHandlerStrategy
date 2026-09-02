package lin.rule.handler

import lin.bean.ComboCard
import lin.bean.cardExt.base.intentEvaluatorRoots
import lin.config.ConfigDispatcher
import lin.config.EvaluatorTreeRoot
import lin.domain.MyWarManage
import lin.rule.context.RuleContext
import lin.rule.context.RuleEnv
import lin.rule.tree.*
import lin.serviceLoader.provider.StartupTask
import lin.serviceLoader.provider.TreeConfigProvider
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
                // D-007「树分皆战术信号」：树分单值聚合，无通道解析（Q-008 通道随 T-018 清理）
                val root = EvaluatorTreeRoot(root = instance.root)

                // 按 instance.bindingType 分发给对应的 Finder
                when (instance.bindingType) {
                    EvaluatorTreeBindingType.GROUP -> {
                        configDispatcher.processByType(
                            instance.bindingIds.map { BindingGroupId(it) },
                            listOf(root)
                        )
                    }

                    EvaluatorTreeBindingType.PURPOSE_TAG -> {
                        configDispatcher.processByType(
                            instance.bindingIds.map { PurposeTagBindingId(it) },
                            listOf(root)
                        )
                    }

                    EvaluatorTreeBindingType.CARD -> {
                        configDispatcher.processByType(
                            instance.bindingIds.map { CardBindingId(it) },
                            listOf(root)
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
 * 需要一个 [RuleEnv] 作参数；生产环境统一用 `warManage.ruleEnv`（决策批次共享快照，
 * 见 [lin.domain.MyWarManage.ruleEnv] 的失效契约），勿自行构造。
 *
 * 返回值是纯值聚合（[RuleResult.Accumulate]），不携带控制语义：
 * - "不参与评分"由守卫未命中 + missValue=0 表达（SCORE）。
 * - 门控短路（[EvalOutcome.Pruned]）在根节点层面视为该根树不贡献分，继续下一棵根树。
 * - 全局禁止（Banned）通过 [EvalSignal.Banned] 异常穿透到编排层，
 *   副作用（card.unUse()）统一由 weightEvaluator 处理。
 */
// @defect purpose-tag-extension/K-001: roots 顺序累加，跨 bindingType（GROUP/PURPOSE_TAG/CARD）不去重不覆盖——
// PURPOSE_TAG 树的"全局兜底"实为 additive，与 fallback 语义冲突（双倍计分风险）。BAN(constraint) 例外：
// EvalOutcome.Banned 一票否决穿透，天然无冲突。加分方向 tag 树案例出现前保持现状。
fun evaluateCardRoots(
    card: ComboCard,
    warManage: MyWarManage,
    ruleEnv: RuleEnv,
): RuleResult.Accumulate {
    var totalScore = 0.0
    val collectedActions = mutableListOf<ComboCardAction>()

    card.intentEvaluatorRoots()?.let { roots ->
        val context = RuleContext(card)
        for (root in roots) {
            when (val res = evaluateConditionTree(root.root, context, ruleEnv, collectedActions)) {
                is EvalOutcome.Matched -> totalScore += res.score
                is EvalOutcome.Skipped -> totalScore += res.score
                // 门控短路：该根树不贡献分（等价于整树 0 分），继续下一棵根树
                EvalOutcome.Pruned -> {}
                EvalOutcome.Banned -> throw EvalSignal.Banned // 全局禁止，穿透到编排层
            }
        }
    }

    return RuleResult.Accumulate(totalScore, collectedActions)
}

/**
 * 面向 AST 条件树的组合求值与意图收集器
 */
fun evaluateConditionTree(
    node: EvaluatorInstanceNode,
    context: RuleContext,
    ruleEnv: RuleEnv,
    collectedActions: MutableList<ComboCardAction>
): EvalOutcome {
    return when (node) {
        is EvaluatorInstanceNode.RuleNode -> {
            val outcome = node.leafLogic(context, ruleEnv)
            if (outcome is EvalOutcome.Matched && outcome.modifyCard != null) {
                collectedActions.add(outcome.modifyCard)
            }
            outcome
        }

        is EvaluatorInstanceNode.AndNode -> {
            var totalScore = 0.0
            var anyMatched = false
            for (child in node.children) {
                when (val res = evaluateConditionTree(child, context, ruleEnv, collectedActions)) {
                    is EvalOutcome.Matched -> {
                        anyMatched = true
                        totalScore += res.score
                    }

                    is EvalOutcome.Skipped -> totalScore += res.score
                    // 门控短路：任一子节点 Pruned → 整棵 AND 子树终止（后续子节点不评估）
                    EvalOutcome.Pruned -> return EvalOutcome.Pruned
                    // 全局禁止：向上穿透
                    EvalOutcome.Banned -> return EvalOutcome.Banned
                }
            }
            if (anyMatched) EvalOutcome.Matched(totalScore) else EvalOutcome.Skipped(totalScore)
        }

        is EvaluatorInstanceNode.OrNode -> {
            for (child in node.children) {
                val localActions = mutableListOf<ComboCardAction>()
                when (val res = evaluateConditionTree(child, context, ruleEnv, localActions)) {
                    is EvalOutcome.Matched -> {
                        collectedActions.addAll(localActions)
                        return res
                    }
                    // 未命中：该分支放弃，尝试下一个子节点
                    is EvalOutcome.Skipped -> {}
                    // 门控短路：任一子节点 Pruned → 整棵 OR 子树终止
                    EvalOutcome.Pruned -> return EvalOutcome.Pruned
                    // 全局禁止：向上穿透
                    EvalOutcome.Banned -> return EvalOutcome.Banned
                }
            }
            EvalOutcome.Skipped(score = 0.0)
        }

        is EvaluatorInstanceNode.BranchNode -> {
            if (node.condition(context, ruleEnv)) {
                evaluateConditionTree(node.onTrue, context, ruleEnv, collectedActions)
            } else {
                evaluateConditionTree(node.onFalse, context, ruleEnv, collectedActions)
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
            this.useAfterStrategy = (this.useAfterStrategy ?: mutableListOf()).apply { addAll(it) }
        }

        intent.useBeforeStrategies?.let {
            this.useBeforeStrategy = (this.useBeforeStrategy ?: mutableListOf()).apply { addAll(it) }
        }

        // T-002：旧排序通道写入保留（useGroupId/useGroupOrder 无排序消费方；硬币识别 T-004 后亦不再读此通道，
        // 仅剩旧 DB 兼容）。勿扩展此通道，排序语义归属于 UseStage/orderWeight。
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



