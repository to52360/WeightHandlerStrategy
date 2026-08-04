package lin.rule.build

import lin.rule.context.RuleContext
import lin.rule.context.RuleEnv
import lin.rule.handler.EvalOutcome
import lin.rule.handler.RuleResult
import lin.rule.parse.FieldParser
import lin.rule.parse.FieldSpec
import lin.rule.score.ScoreEffect
import lin.rule.tree.EvaluatorLeafConfig
import kotlin.reflect.KClass

/** 规则逻辑：只产评分（Continue），控制语义（missValue 兜底 / Banned 禁止）由守卫侧 guardMissBehavior 决定，规则不参与控制 */
typealias RuleLogic = RuleContext.(RuleEnv) -> RuleResult

/** 叶子节点求值闭包：返回 [EvalOutcome] 三态，由守卫结果 + rule 评分组合而成 */
typealias LeafLogic = RuleContext.(RuleEnv) -> EvalOutcome

typealias RuleFactory<T> = (EvaluatorLeafConfig, T) -> RuleLogic

/**
 * 泛型不支持基础类型
 */
class RuleBuilder<T : Any>(
    private val parameterType: KClass<T>
) {
    private lateinit var id: String
    private lateinit var factory: RuleFactory<T>
    private var metadata: RuleMetadata? = null
        get() {
            if (field == null) field = RuleMetadata(id, id)
            return field!!
        }
    private var defaultScoreEffect: ScoreEffect = ScoreEffect.ConstantScore(0.0)

    // 统一存放字段提供者：单条/批量都视为 () -> List<FieldSpec>，build 时 flatMap 展开
    private val extraFields = mutableListOf<() -> List<FieldSpec>>()

    fun id(id: String) = apply { this.id = id }
    fun factory(factory: RuleFactory<T>) = apply { this.factory = factory }
    fun metadata(metadata: RuleMetadata) = apply { this.metadata = metadata }
    fun metadata(name: String, desc: String? = null) = apply { this.metadata = RuleMetadata(name, desc) }

    fun defaultScoreEffect(effect: ScoreEffect) = apply { this.defaultScoreEffect = effect }

    fun extraField(spec: FieldSpec) = apply {
        extraFields.add { listOf(spec) }
    }

    fun extraFieldLazy(specProvider: () -> FieldSpec) = apply {
        extraFields.add { listOf(specProvider()) }
    }

    fun extraFieldsLazy(specsProvider: () -> List<FieldSpec>) = apply {
        extraFields.add(specsProvider)
    }

    fun build(): RuleRegistration<T> {
        val snapshot = extraFields.toList()
        return RuleRegistration(
            ruleId = id,
            metadata = metadata,
            parameterType = parameterType,
            ruleFactory = factory,
            defaultScoreEffect = defaultScoreEffect,
            // [完全延迟解析]：所有字段的反射在前端拉取表单时才真正执行
            lazyFieldsResolver = {
                FieldParser.parse(parameterType) + snapshot.flatMap { it() }
            }
        )
    }
}





