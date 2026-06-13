package lin.rule.handler

import lin.bean.ComboCard
import lin.bean.cardExt.base.intentEvaluatorRoots
import lin.config.ConfigDispatcher
import lin.config.EvaluatorTreeRoot
import lin.domain.MyWarManage
import lin.rule.build.RuleLogic
import lin.rule.condition.*
import lin.rule.condition.orthogonal.ConditionAssembler
import lin.rule.context.RuleContext
import lin.rule.context.RuleEnv
import lin.rule.parse.extractPrefixedArgs
import lin.rule.registry.RuleRegistry
import lin.rule.tree.*
import lin.serviceLoader.provider.config.ConditionTreeConfigProvider
import lin.serviceLoader.provider.config.TreeConfigProvider
import lin.utils.startup.StartupTask
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/**
 * 启动任务：将 TreeConfigProvider 中的评估树绑定到 ConfigDispatcher。
 */
class RuleTreeBindingTask : StartupTask, KoinComponent {

    override fun execute() {
        val ruleRegistry = get<RuleRegistry>()
        val conditionRegistry = get<ConditionRegistry>()
        val configDispatcher = get<ConfigDispatcher>()
        val providers = getKoin().getAll<TreeConfigProvider>()
        val conditionTreeProviders = getKoin().getAll<ConditionTreeConfigProvider>()

        for (provider in providers) {
            for (config in provider.findAll()) {
                val instance = config.instantiate { leafConfig ->
                    buildEvaluatorLeafLogic(leafConfig, ruleRegistry, conditionRegistry, conditionTreeProviders)
                }

                // 按 binding.type 分发给对应的 Finder
                val groupBindings = instance.bindings.filter { it.type == EvaluatorTreeBindingType.GROUP }
                if (groupBindings.isNotEmpty()) {
                    configDispatcher.processByType(
                        groupBindings.map { BindingGroupId(it.id) },
                        listOf(EvaluatorTreeRoot(instance.root))
                    )
                }
                val tagBindings = instance.bindings.filter { it.type == EvaluatorTreeBindingType.PURPOSE_TAG }
                if (tagBindings.isNotEmpty()) {
                    configDispatcher.processByType(
                        tagBindings.map { PurposeTagBindingId(it.id) },
                        listOf(EvaluatorTreeRoot(instance.root))
                    )
                }
            }
        }
    }
}

internal fun buildEvaluatorLeafLogic(
    leafConfig: EvaluatorLeafConfig,
    ruleRegistry: RuleRegistry,
    conditionRegistry: ConditionRegistry,
    conditionTreeProviders: List<ConditionTreeConfigProvider>
): RuleLogic {
    val assembler = conditionRegistry.conditionAssembler
        ?: error("ConditionAssembler is not configured in this context")
    // ARCH-UNSETTLED(validation, U-002): 绑定时做前置校验，是否存在防御过度 | next: 评估叶子节点参数是否可仅在配置端做单点校验
    return when (leafConfig.sourceType) {
        EvaluatorLeafSourceType.RULE -> {
            val registration = ruleRegistry.require(leafConfig.sourceId)
            validateAndThrow("规则", leafConfig.sourceId, leafConfig.args, registration.lazyFieldsResolver())
            val originalLogic = ruleRegistry.build(leafConfig)
            originalLogic.wrapWithDynamicWeight(leafConfig, assembler)
        }

        EvaluatorLeafSourceType.CONDITION -> {
            val registration = conditionRegistry.require(leafConfig.sourceId)
            validateAndThrow("条件", leafConfig.sourceId, leafConfig.args, listOf(registration.field.toFieldSpec()))
            val conditionLogic = conditionRegistry.build(leafConfig.sourceId, leafConfig.args)
            conditionLogic.toWeightedRuleLogic(leafConfig, assembler)
        }

        EvaluatorLeafSourceType.CONDITION_TREE -> {
            val conditionTree = conditionTreeProviders
                .firstNotNullOfOrNull { it.findById(leafConfig.sourceId) }
                ?: error("Condition tree config not found: sourceId=${leafConfig.sourceId}")
            val args = leafConfig.args
            val conditionRefs = conditionTree.root.collectConditionRefs().distinctBy { it.refId }
            for (ref in conditionRefs) {
                val conditionArgs = args.extractPrefixedArgs(ref.refId)
                when (ref) {
                    is ConditionPayload.ConditionRef -> {
                        val registration = conditionRegistry.require(ref.conditionId)
                        validateAndThrow(
                            contextName = "条件树 [${leafConfig.sourceId}] 嵌套条件",
                            id = ref.refId,
                            args = conditionArgs,
                            fields = listOf(registration.field.toFieldSpec())
                        )
                    }

                    is ConditionPayload.OrthogonalRef -> {
                        val operator = assembler.findOperator(ref.operatorId)
                            ?: error("Operator not found: ${ref.operatorId}")
                        validateAndThrow(
                            contextName = "条件树 [${leafConfig.sourceId}] 嵌套正交条件",
                            id = ref.refId,
                            args = conditionArgs,
                            fields = operator.paramSpecs
                        )
                    }
                }
            }
            val conditionLogic = conditionTree.root.compile { ref ->
                val conditionArgs = args.extractPrefixedArgs(ref.refId)
                val newPayload = when (ref) {
                    is ConditionPayload.ConditionRef -> ref.copy(args = conditionArgs)
                    is ConditionPayload.OrthogonalRef -> ref.copy(args = conditionArgs)
                }
                conditionRegistry.build(newPayload)
            }
            conditionLogic.toWeightedRuleLogic(leafConfig, assembler)
        }
    }
}

private fun validateAndThrow(
    contextName: String,
    id: String,
    args: Map<String, Any?>,
    fields: List<lin.rule.parse.FieldSpec>
) {
    val validation = lin.rule.parse.SpecValidator.validate(args, fields)
    if (!validation.isValid) {
        val detail = validation.errors.joinToString { it.message }
        lin.myLog.error { "$contextName [$id] 绑定校验失败: $detail" }
        throw IllegalArgumentException("$contextName [$id] 绑定校验失败: $detail")
    }
}

private fun RuleLogic.wrapWithDynamicWeight(
    leafConfig: EvaluatorLeafConfig,
    assembler: ConditionAssembler
): RuleLogic {
    val originalLogic = this
    val weightSource = leafConfig.weightSourceId?.let { id ->
        assembler.findDataSource(id) ?: error("Weight multiplier DataSource not found: $id")
    }
    weightSource?.let {
        val type = it.outputType.javaObjectType
        require(Number::class.java.isAssignableFrom(type)) {
            "Weight multiplier DataSource [${it.id}] must output a numeric type, but got [${it.outputType}]"
        }
    }
    if (weightSource == null) return originalLogic

    return {
        val result = originalLogic(this)
        if (result is RuleResult.Continue) {
            val multiplier = weightSource.resolve(this) as Number
            result.copy(score = result.score * multiplier.toDouble())
        } else {
            result
        }
    }
}

private fun ConditionLogic.toWeightedRuleLogic(
    leafConfig: EvaluatorLeafConfig,
    assembler: ConditionAssembler
): RuleLogic {
    val conditionLogic = this
    val weightSource = leafConfig.weightSourceId?.let { id ->
        assembler.findDataSource(id) ?: error("Weight multiplier DataSource not found: $id")
    }
    val mismatchedWeightSource = leafConfig.mismatchedWeightSourceId?.let { id ->
        assembler.findDataSource(id) ?: error("Mismatched weight multiplier DataSource not found: $id")
    }

    weightSource?.let {
        val type = it.outputType.javaObjectType
        require(Number::class.java.isAssignableFrom(type)) {
            "Weight multiplier DataSource [${it.id}] must output a numeric type, but got [${it.outputType}]"
        }
    }
    mismatchedWeightSource?.let {
        val type = it.outputType.javaObjectType
        require(Number::class.java.isAssignableFrom(type)) {
            "Mismatched weight multiplier DataSource [${it.id}] must output a numeric type, but got [${it.outputType}]"
        }
    }

    val logic: RuleLogic = {
        val matched = conditionLogic(this)
        val score = if (matched) {
            val base = leafConfig.weight
            if (weightSource != null) base * (weightSource.resolve(this) as Number).toDouble() else base
        } else {
            val base = leafConfig.mismatchedWeight
            if (mismatchedWeightSource != null) base * (mismatchedWeightSource.resolve(this) as Number).toDouble() else base
        }
        RuleResult.Continue(score = score)
    }
    return logic
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
            if (res is RuleResult.Prune) return RuleResult.Accumulate(totalScore, collectedActions, pruned = true)
            if (res is RuleResult.Continue) {
                totalScore += res.score
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
