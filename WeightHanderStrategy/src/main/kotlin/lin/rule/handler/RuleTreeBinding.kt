package lin.rule.handler

import lin.bean.ComboCard
import lin.bean.cardExt.base.intentEvaluatorRoots
import lin.config.ConfigDispatcher
import lin.config.EvaluatorTreeRoot
import lin.domain.MyWarManage
import lin.rule.build.RuleLogic
import lin.rule.condition.*
import lin.rule.context.RuleContext
import lin.rule.context.RuleEnv
import lin.rule.orthogonal.Transform
import lin.rule.parse.extractPrefixedArgs
import lin.rule.registry.RuleRegistry
import lin.rule.score.ScoreEffect
import lin.rule.score.ScoreOperator
import lin.rule.score.ScoreOperatorRegistry
import lin.rule.tree.*
import lin.serviceLoader.provider.ConditionTreeConfigProvider
import lin.serviceLoader.provider.TreeConfigProvider
import lin.utils.startup.StartupTask
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject
import kotlin.reflect.KType
import kotlin.reflect.full.isSubtypeOf

internal object RuleTreeBindingHelper : KoinComponent {
    val scoreOperatorRegistry: ScoreOperatorRegistry by inject()
}

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
                val instance = config.instantiate(
                    leafBuilder = { leafConfig ->
                        buildEvaluatorLeafLogic(leafConfig, ruleRegistry, conditionRegistry, conditionTreeProviders)
                    },
                    branchConditionBuilder = { leafConfig ->
                        buildBranchConditionLogic(leafConfig, conditionRegistry, conditionTreeProviders)
                    }
                )

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
    val assembler = conditionRegistry.pipelineAssembler
        ?: error("PipelineAssembler is not configured in this context")

    // 1. 获取守卫条件逻辑 (GuardLogic)
    val guardLogic: ConditionLogic? = when (leafConfig) {
        is ConditionLeafConfig -> {
            val payload = leafConfig.resolveConditionPayload()
            if (payload is ConditionPayload.ConditionRef) {
                val registration = conditionRegistry.require(payload.conditionId)
                validateAndThrow("条件", payload.conditionId, payload.args, listOf(registration.field.toFieldSpec()))
            } else if (payload is ConditionPayload.PipelineRef) {
                for (call in payload.transforms) {
                    val transform = assembler.findTransform(call.transformId)
                        ?: error("Transform not found: ${call.transformId}")
                    validateAndThrow("管道步骤 [${transform.id}]", payload.refId, call.args, transform.fields)
                }
                val operator = assembler.findOperator(payload.operatorId)
                    ?: error("Operator not found: ${payload.operatorId}")
                validateAndThrow("管道算子 [${operator.id}]", payload.refId, payload.operatorArgs, operator.paramSpecs)
            }
            conditionRegistry.build(payload) as ConditionLogic?
        }

        is ConditionTreeLeafConfig -> {
            buildConditionTreeLogic(leafConfig, conditionRegistry, conditionTreeProviders) as ConditionLogic?
        }

        is RuleLeafConfig -> {
            leafConfig.guardCondition?.let { conditionRegistry.build(it) as ConditionLogic? }
        }

        is OrthogonalRuleLeafConfig -> {
            leafConfig.guardCondition?.let { conditionRegistry.build(it) as ConditionLogic? }
        }
    }

    // 2. 获取核心得分逻辑 (ScoreLogic)
    val scoreLogic: RuleLogic = when (leafConfig) {
        is RuleLeafConfig -> {
            val registration = ruleRegistry.require(leafConfig.sourceId)
            validateAndThrow("规则", leafConfig.sourceId, leafConfig.args, registration.lazyFieldsResolver())
            ruleRegistry.build(leafConfig)
        }

        else -> {
            val scoreEffectLogic = compileScoreEffect(leafConfig, assembler)
            val logicClosure: RuleLogic = {
                RuleResult.Continue(score = scoreEffectLogic(this))
            }
            logicClosure
        }
    }

    // 3. 获取未命中分值 (MissValue)
    val missValue = when (leafConfig) {
        is RuleLeafConfig -> leafConfig.missValue
        is OrthogonalRuleLeafConfig -> when (val effect = leafConfig.scoreEffect) {
            is ScoreEffect.ConstantScore -> effect.missValue
            is ScoreEffect.SourceScore -> effect.missValue
        }

        is ConditionLeafConfig -> when (val effect = leafConfig.scoreEffect) {
            is ScoreEffect.ConstantScore -> effect.missValue
            is ScoreEffect.SourceScore -> effect.missValue
        }

        is ConditionTreeLeafConfig -> when (val effect = leafConfig.scoreEffect) {
            is ScoreEffect.ConstantScore -> effect.missValue
            is ScoreEffect.SourceScore -> effect.missValue
        }
    }


    // 4. 归一化执行闭包，通过命名函数绕开 Lambda 内部 Context Receiver 的匹配漏洞
    val finalLogic: RuleLogic = {
        val matched = if (guardLogic != null) {
            invokeCondition(guardLogic, this)
        } else {
            true
        }
        if (matched) {
            invokeRule(scoreLogic, this)
        } else {
            RuleResult.Continue(score = missValue)
        }
    }
    return finalLogic
}

internal fun buildBranchConditionLogic(
    leafConfig: EvaluatorLeafConfig,
    conditionRegistry: ConditionRegistry,
    conditionTreeProviders: List<ConditionTreeConfigProvider>
): ConditionLogic {
    val assembler = conditionRegistry.pipelineAssembler
        ?: error("PipelineAssembler is not configured in this context")
    return when (leafConfig) {
        is ConditionLeafConfig -> {
            val payload = leafConfig.resolveConditionPayload()
            if (payload is ConditionPayload.ConditionRef) {
                val registration = conditionRegistry.require(payload.conditionId)
                validateAndThrow(
                    "分支条件",
                    payload.conditionId,
                    payload.args,
                    listOf(registration.field.toFieldSpec())
                )
            } else if (payload is ConditionPayload.PipelineRef) {
                for (call in payload.transforms) {
                    val transform = assembler.findTransform(call.transformId)
                        ?: error("Transform not found: ${call.transformId}")
                    validateAndThrow("分支管道步骤 [${transform.id}]", payload.refId, call.args, transform.fields)
                }
                val operator = assembler.findOperator(payload.operatorId)
                    ?: error("Operator not found: ${payload.operatorId}")
                validateAndThrow(
                    "分支管道算子 [${operator.id}]",
                    payload.refId,
                    payload.operatorArgs,
                    operator.paramSpecs
                )
            }
            conditionRegistry.build(payload) as ConditionLogic
        }

        is ConditionTreeLeafConfig -> {
            buildConditionTreeLogic(
                leafConfig,
                conditionRegistry,
                conditionTreeProviders
            ) as ConditionLogic
        }

        is RuleLeafConfig, is OrthogonalRuleLeafConfig -> {
            error("Branch control node cannot bind RULE: nodeId=${leafConfig.nodeId}")
        }
    }
}

private fun buildConditionTreeLogic(
    leafConfig: EvaluatorLeafConfig,
    conditionRegistry: ConditionRegistry,
    conditionTreeProviders: List<ConditionTreeConfigProvider>
): ConditionLogic {
    val assembler = conditionRegistry.pipelineAssembler
        ?: error("PipelineAssembler is not configured in this context")
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

            is ConditionPayload.PipelineRef -> {
                for (call in ref.transforms) {
                    val transform = assembler.findTransform(call.transformId)
                        ?: error("Transform not found: ${call.transformId}")
                    validateAndThrow(
                        contextName = "条件树 [${leafConfig.sourceId}] 嵌套管道步骤 [${transform.id}]",
                        id = ref.refId,
                        args = call.args,
                        fields = transform.fields
                    )
                }
                val operator = assembler.findOperator(ref.operatorId)
                    ?: error("Operator not found: ${ref.operatorId}")
                val finalOpArgs = ref.operatorArgs + conditionArgs
                validateAndThrow(
                    contextName = "条件树 [${leafConfig.sourceId}] 嵌套管道算子 [${operator.id}]",
                    id = ref.refId,
                    args = finalOpArgs,
                    fields = operator.paramSpecs
                )
            }
        }
    }
    return conditionTree.root.compile { ref ->
        val conditionArgs = args.extractPrefixedArgs(ref.refId)
        val newPayload = when (ref) {
            is ConditionPayload.ConditionRef -> ref.copy(args = conditionArgs)
            is ConditionPayload.PipelineRef -> {
                if (conditionArgs.isNotEmpty()) {
                    ref.copy(operatorArgs = ref.operatorArgs + conditionArgs)
                } else {
                    ref
                }
            }
        }
        conditionRegistry.build(newPayload)
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


private typealias ScoreLogic = context(RuleEnv) RuleContext.() -> Double

private fun compileScoreEffect(
    leafConfig: EvaluatorLeafConfig,
    assembler: PipelineAssembler
): ScoreLogic {
    val effect = when (leafConfig) {
        is OrthogonalRuleLeafConfig -> leafConfig.scoreEffect
        is ConditionLeafConfig -> leafConfig.scoreEffect
        is ConditionTreeLeafConfig -> leafConfig.scoreEffect
        is RuleLeafConfig -> ScoreEffect.ConstantScore(0.0)
    }
    return when (effect) {
        is ScoreEffect.ConstantScore -> {
            { effect.value }
        }

        is ScoreEffect.SourceScore -> compileSourceScore(effect, assembler)
    }
}


private fun compileSourceScore(
    effect: ScoreEffect.SourceScore,
    assembler: PipelineAssembler
): ScoreLogic {
    val source = assembler.findDataSource(effect.sourceId)
        ?: error("Score DataSource not found: ${effect.sourceId}")

    var currentType: KType = source.outputType

    // 1. 验证 Transforms
    val transformInstances = effect.transforms.map { call ->
        @Suppress("UNCHECKED_CAST")
        val transform = assembler.findTransform(call.transformId) as? Transform<Any, Any>
            ?: error("Transform not found: ${call.transformId}")

        require(currentType.isSubtypeOf(transform.inputType)) {
            "Type mismatch: previous output type [$currentType] is not compatible with Transform [${transform.id}] input type [${transform.inputType}]"
        }

        val validation = lin.rule.parse.SpecValidator.validate(call.args, transform.fields)
        if (!validation.isValid) {
            throw IllegalArgumentException("评分转换器 [${transform.id}] 参数校验失败: ${validation.errors.joinToString { it.message }}")
        }

        currentType = transform.outputType
        transform to call.args
    }

    // 2. 验证 ScoreOperator
    @Suppress("UNCHECKED_CAST")
    val operator = RuleTreeBindingHelper.scoreOperatorRegistry.find(effect.operatorId) as? ScoreOperator<Any, Any>
        ?: error("ScoreOperator not found: ${effect.operatorId}")

    // 检查兼容性
    require(currentType.isSubtypeOf(operator.inputType)) {
        "Type mismatch: final pipeline output type [$currentType] is not compatible with ScoreOperator [${operator.id}] input type [${operator.inputType}]"
    }

    // 校验算子参数
    val validation = lin.rule.parse.SpecValidator.validate(effect.operatorArgs, operator.paramSpecs)
    if (!validation.isValid) {
        lin.myLog.error { "评分效应 [${effect.operatorId}] 算子参数校验失败: ${validation.errors.joinToString { it.message }}" }
        throw IllegalArgumentException("评分效应 [${effect.operatorId}] 校验失败: ${validation.errors.joinToString { it.message }}")
    }

    // SourceScore 参数复用规则参数 ObjectMapper 进行转换，由单元测试覆盖验证
    val parameter = lin.rule.parse.mapToRuleArgs(effect.operatorArgs, operator.parameterType)
    return {
        var currentVal: Any = source.resolve(this)

        for ((transform, args) in transformInstances) {
            currentVal = transform.transform(currentVal, this, args)
        }

        operator.score(currentVal, parameter)
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

context(env: RuleEnv)
private fun invokeCondition(logic: ConditionLogic, context: RuleContext): Boolean {
    return logic(env, context)
}

context(env: RuleEnv)
private fun invokeRule(logic: RuleLogic, context: RuleContext): RuleResult {
    return logic(env, context)
}

