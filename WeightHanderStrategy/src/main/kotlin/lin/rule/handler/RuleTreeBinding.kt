package lin.rule.handler

import lin.bean.ComboCard
import lin.bean.cardExt.base.intentEvaluatorRoots
import lin.bean.usePlan.*
import lin.config.ConfigDispatcher
import lin.config.EvaluatorTreeRoot
import lin.domain.MyWarManage
import lin.rule.context.RuleContext
import lin.rule.context.RuleEnv
import lin.rule.tree.*
import lin.serviceLoader.provider.GroupBehaviorProvider
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
        val groupBindings = getKoin().getOrNull<GroupBehaviorProvider>()?.provide().orEmpty()
        val tagRuleIndex = (getKoin().getOrNull<PurposeTagIntentRuleProvider>()
            ?: DefaultPurposeTagIntentRuleProvider()).rules().associateBy { it.tagId }

        for (provider in providers) {
            for (config in provider.findAll()) {
                val instance = config.instantiate(
                    leafBuilder = logicAssembler::build,
                    branchConditionBuilder = logicAssembler::buildBranch
                )
                // channel 静态解析：树显式 > 绑定目标缺省（GROUP/PURPOSE_TAG/CARD）> GENERAL
                val root = EvaluatorTreeRoot(
                    root = instance.root,
                    channel = resolveChannel(config, groupBindings, tagRuleIndex)
                )

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

    /**
     * channel 解析（Q-008 定论）：
     * 1. 树显式声明 `EvaluatorTreeConfig.channel` 最高优先。
     * 2. 否则由绑定目标的候选策略推导缺省（[ScoreChannel.fromCandidatePolicy]）：
     *    GROUP→GroupUseOverride.candidatePolicy、PURPOSE_TAG→PurposeTagIntentRule.defaultCandidatePolicy、CARD→GENERAL。
     * 3. 一棵树绑多个 bindingId 时取缺省唯一值；不一致抛配置冲突，要求树显式声明。
     *    （CARD 树的单卡显式 TACTICS_DOMINANT 边界：需 CardPurpose 数据，RuleTreeBindingTask 拿不到，见 TRACKER Q-008 追加待办。）
     */
    private fun resolveChannel(
        config: EvaluatorTreeConfig,
        groupBindings: List<CardGroupBinding>,
        tagRuleIndex: Map<PurposeTagId, PurposeTagIntentRule>
    ): ScoreChannel {
        config.channel?.let { return it }
        val derived = when (config.bindingType) {
            EvaluatorTreeBindingType.GROUP -> config.bindingIds.mapNotNull { id ->
                groupBindings.find { it.id == id }
                    ?.behaviors?.findOverride()?.candidatePolicy
                    ?.let { ScoreChannel.fromCandidatePolicy(it) }
            }

            EvaluatorTreeBindingType.PURPOSE_TAG -> config.bindingIds.mapNotNull { id ->
                tagRuleIndex[PurposeTagId(id)]?.defaultCandidatePolicy
                    ?.let { ScoreChannel.fromCandidatePolicy(it) }
            }

            EvaluatorTreeBindingType.CARD -> emptyList()
        }.distinct()
        return when (derived.size) {
            0 -> ScoreChannel.GENERAL
            1 -> derived.first()
            else -> throw IllegalStateException(
                "评分通道冲突：评估树 ${config.bindingType} ${config.bindingIds} 的绑定目标推导出多个不同的缺省 channel（$derived），" +
                        "请在树配置上显式声明 channel"
            )
        }
    }
}


// ────────────────────────────────────────────────────────────
// 顶层函数：供编排函数调用
// ────────────────────────────────────────────────────────────

/**
 * 对一张卡牌的所有意图评估根节点执行条件树求值。
 * 需要在 [RuleEnv] 作用域内调用（例如 `with(WarInfoEnv(warManage))`）。
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
    var generalScore = 0.0
    var tacticalScore = 0.0
    val collectedActions = mutableListOf<ComboCardAction>()

    card.intentEvaluatorRoots()?.let { roots ->
        val context = RuleContext(card)
        for (root in roots) {
            when (val res = evaluateConditionTree(root.root, context, ruleEnv, collectedActions)) {
                is EvalOutcome.Matched -> addToChannel(
                    root.channel,
                    res.score,
                    { generalScore += it },
                    { tacticalScore += it })

                is EvalOutcome.Skipped -> addToChannel(
                    root.channel,
                    res.score,
                    { generalScore += it },
                    { tacticalScore += it })
                // 门控短路：该根树不贡献分（等价于整树 0 分），继续下一棵根树
                EvalOutcome.Pruned -> {}
                EvalOutcome.Banned -> throw EvalSignal.Banned // 全局禁止，穿透到编排层
            }
        }
    }

    return RuleResult.Accumulate(generalScore, tacticalScore, collectedActions)
}

private inline fun addToChannel(
    channel: ScoreChannel,
    score: Double,
    addGeneral: (Double) -> Unit,
    addTactical: (Double) -> Unit
) {
    when (channel) {
        ScoreChannel.GENERAL -> addGeneral(score)
        ScoreChannel.TACTICAL -> addTactical(score)
    }
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



