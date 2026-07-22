package lin.rule.handler

import lin.rule.build.LeafLogic
import lin.rule.build.RuleLogic
import lin.rule.condition.*
import lin.rule.context.RuleContext
import lin.rule.context.RuleEnv
import lin.rule.orthogonal.PipelineCacheKeys
import lin.rule.orthogonal.Transform
import lin.rule.orthogonal.evaluatePipelineOutput
import lin.rule.parse.extractPrefixedArgs
import lin.rule.registry.RuleRegistry
import lin.rule.score.ScoreEffect
import lin.rule.score.ScoreOperator
import lin.rule.score.ScoreOperatorRegistry
import lin.rule.tree.*
import lin.serviceLoader.provider.ConditionTreeConfigProvider
import kotlin.reflect.KType
import kotlin.reflect.full.isSubtypeOf

// ── 守卫编译 ──

/**
 * 守卫逻辑编译：查注册表、验参数、构建 [ConditionLogic]。
 * 只依赖条件相关服务，不碰评分。
 */
class GuardCompiler(
    private val conditionRegistry: ConditionRegistry,
    private val conditionTreeProviders: List<ConditionTreeConfigProvider>,
    private val assembler: PipelineAssembler
) {
    fun compile(leafConfig: EvaluatorLeafConfig): ConditionLogic? {
        return when (leafConfig) {
            is ConditionLeafConfig -> compileCoded(leafConfig)
            is OrthogonalConditionLeafConfig -> compileOrthogonal(leafConfig)
            is ConditionTreeLeafConfig -> buildConditionTreeLogic(leafConfig)
            is EvaluatorLeafConfig.Rule -> compileRule(leafConfig)
        }
    }

    private fun compileCoded(leafConfig: ConditionLeafConfig): ConditionLogic? {
        val payload = leafConfig.resolveConditionPayload()
        if (payload is ConditionPayload.ConditionRef) {
            val registration = conditionRegistry.require(payload.conditionId)
            validate("条件", payload.conditionId, payload.args, listOf(registration.field.toFieldSpec()))
        } else if (payload is ConditionPayload.PipelineRef) {
            validatePipeline("管道步骤", "管道算子", payload)
        }
        return conditionRegistry.build(payload) as ConditionLogic?
    }

    private fun compileOrthogonal(leafConfig: OrthogonalConditionLeafConfig): ConditionLogic? {
        val payload = leafConfig.guardCondition
        validatePipeline("正交条件管道步骤", "正交条件管道算子", payload)
        return conditionRegistry.build(payload) as ConditionLogic?
    }

    private fun compileRule(leafConfig: EvaluatorLeafConfig.Rule): ConditionLogic? {
        return leafConfig.guardCondition?.let { conditionRegistry.build(it) as ConditionLogic? }
    }

    private fun validatePipeline(
        stepLabel: String, operatorLabel: String, payload: ConditionPayload.PipelineRef
    ) {
        for (call in payload.transforms) {
            val transform = assembler.findTransform(call.transformId)
                ?: error("Transform not found: ${call.transformId}")
            validate("$stepLabel [${transform.id}]", payload.refId, call.args, transform.fields)
        }
        val operator = assembler.findOperator(payload.operatorId)
            ?: error("Operator not found: ${payload.operatorId}")
        validate("$operatorLabel [${operator.id}]", payload.refId, payload.operatorArgs, operator.paramSpecs)
    }

    // ── 条件树编译 ──

    private fun buildConditionTreeLogic(leafConfig: ConditionTreeLeafConfig): ConditionLogic {
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
                    validate(
                        "条件树 [${leafConfig.sourceId}] 嵌套条件", ref.refId, conditionArgs,
                        listOf(registration.field.toFieldSpec())
                    )
                }

                is ConditionPayload.PipelineRef -> {
                    for (call in ref.transforms) {
                        val transform = assembler.findTransform(call.transformId)
                            ?: error("Transform not found: ${call.transformId}")
                        validate(
                            "条件树 [${leafConfig.sourceId}] 嵌套管道步骤 [${transform.id}]",
                            ref.refId, call.args, transform.fields
                        )
                    }
                    val operator = assembler.findOperator(ref.operatorId)
                        ?: error("Operator not found: ${ref.operatorId}")
                    validate(
                        "条件树 [${leafConfig.sourceId}] 嵌套管道算子 [${operator.id}]",
                        ref.refId, ref.operatorArgs + conditionArgs, operator.paramSpecs
                    )
                }
            }
        }
        return conditionTree.root.compile { ref ->
            val conditionArgs = args.extractPrefixedArgs(ref.refId)
            val newPayload = when (ref) {
                is ConditionPayload.ConditionRef -> ref.copy(args = conditionArgs)
                is ConditionPayload.PipelineRef ->
                    if (conditionArgs.isNotEmpty()) ref.copy(operatorArgs = ref.operatorArgs + conditionArgs)
                    else ref
            }
            conditionRegistry.build(newPayload)
        }
    }
}

// ── 评分编译 ──

/**
 * 评分逻辑编译：编译编码规则或 SourceScore 管道。
 * 只依赖规则/评分相关服务，不碰条件守卫。
 */
class ScoreCompiler(
    private val ruleRegistry: RuleRegistry,
    private val assembler: PipelineAssembler,
    private val scoreOperatorRegistry: ScoreOperatorRegistry
) {
    fun compile(leafConfig: EvaluatorLeafConfig): RuleLogic {
        return when (leafConfig) {
            is RuleLeafConfig -> compileCoded(leafConfig)
            else -> compileScoreEffect(leafConfig)
        }
    }

    private fun compileCoded(leafConfig: RuleLeafConfig): RuleLogic {
        val registration = ruleRegistry.require(leafConfig.sourceId)
        validate("规则", leafConfig.sourceId, leafConfig.args, registration.lazyFieldsResolver())
        return ruleRegistry.build(leafConfig)
    }

    private fun compileScoreEffect(leafConfig: EvaluatorLeafConfig): RuleLogic {
        val logic = compileScoreEffectLogic(leafConfig)
        return { env -> RuleResult.Continue(score = logic(this, env)) }
    }

    private fun compileScoreEffectLogic(leafConfig: EvaluatorLeafConfig): ScoreLogic {
        return when (val effect = (leafConfig as Scoreable).scoreEffect) {
            is ScoreEffect.ConstantScore -> {
                { effect.value }
            }

            is ScoreEffect.SourceScore -> compileSourceScore(effect)
        }
    }

    private fun compileSourceScore(effect: ScoreEffect.SourceScore): ScoreLogic {
        val source = assembler.findDataSource(effect.sourceId)
            ?: error("Score DataSource not found: ${effect.sourceId}")

        var currentType: KType = source.outputType

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

        @Suppress("UNCHECKED_CAST")
        val operator = scoreOperatorRegistry.find(effect.operatorId) as? ScoreOperator<Any, Any>
            ?: error("ScoreOperator not found: ${effect.operatorId}")

        require(currentType.isSubtypeOf(operator.inputType)) {
            "Type mismatch: final pipeline output type [$currentType] is not compatible with ScoreOperator [${operator.id}] input type [${operator.inputType}]"
        }
        val validation = lin.rule.parse.SpecValidator.validate(effect.operatorArgs, operator.paramSpecs)
        if (!validation.isValid) {
            lin.myLog.error { "评分效应 [${effect.operatorId}] 算子参数校验失败: ${validation.errors.joinToString { it.message }}" }
            throw IllegalArgumentException("评分效应 [${effect.operatorId}] 校验失败: ${validation.errors.joinToString { it.message }}")
        }
        val parameter = lin.rule.parse.mapToRuleArgs(effect.operatorArgs, operator.parameterType)

        val cacheKeys = PipelineCacheKeys.build(effect.sourceId, effect.transforms)

        return { env ->
            val output = evaluatePipelineOutput(
                env = env,
                source = source,
                transformInstances = transformInstances,
                cacheKeys = cacheKeys,
                crossCard = effect.crossCard
            )
            operator.score(output, parameter)
        }
    }
}

// ── 编排 ──

/**
 * 叶子逻辑编排器：组合 [GuardCompiler] 和 [ScoreCompiler] 的结果，
 * 包装为 [LeafLogic] 执行闭包。
 */
class LeafLogicAssembler(
    private val guardCompiler: GuardCompiler,
    private val scoreCompiler: ScoreCompiler
) {
    fun build(leafConfig: EvaluatorLeafConfig): LeafLogic {
        val guardLogic = guardCompiler.compile(leafConfig)
        val scoreLogic = scoreCompiler.compile(leafConfig)
        val missValue = (leafConfig as Scoreable).scoreEffect.missValue
        val guardMissBehavior = leafConfig.guardMissBehavior

        return { env ->
            val guardPassed = guardLogic == null || guardLogic(this, env)
            when {
                guardPassed -> {
                    when (val res = scoreLogic(this, env)) {
                        is RuleResult.Continue -> EvalOutcome.Matched(res.score, res.modifyCard)
                    }
                }

                guardMissBehavior == GuardMissBehavior.PRUNE -> EvalOutcome.Pruned
                else -> EvalOutcome.Skipped(missValue)
            }
        }
    }

    fun buildBranch(leafConfig: EvaluatorLeafConfig): ConditionLogic {
        return when (leafConfig) {
            is EvaluatorLeafConfig.Condition ->
                guardCompiler.compile(leafConfig)
                    ?: error("Branch condition cannot be null: nodeId=${leafConfig.nodeId}")

            is EvaluatorLeafConfig.Rule ->
                error("Branch control node cannot bind RULE: nodeId=${leafConfig.nodeId}")
        }
    }
}

// ── 工具 ──

private typealias ScoreLogic = RuleContext.(RuleEnv) -> Double

private fun validate(
    contextName: String, id: String, args: Map<String, Any?>, fields: List<lin.rule.parse.FieldSpec>
) {
    val validation = lin.rule.parse.SpecValidator.validate(args, fields)
    if (!validation.isValid) {
        val detail = validation.errors.joinToString { it.message }
        lin.myLog.error { "$contextName [$id] 绑定校验失败: $detail" }
        throw IllegalArgumentException("$contextName [$id] 绑定校验失败: $detail")
    }
}


