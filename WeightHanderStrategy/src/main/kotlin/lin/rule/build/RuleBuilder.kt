package lin.rule.build

import lin.rule.context.RuleContext
import lin.rule.context.RuleEnv
import lin.rule.handler.RuleResult
import lin.rule.parse.FieldParser
import lin.rule.parse.FieldSpec
import lin.rule.tree.EvaluatorLeafConfig
import kotlin.reflect.KClass

typealias RuleLogic = context(RuleEnv) RuleContext.() -> RuleResult

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
    private var scoreEffectType: ScoreEffectType = ScoreEffectType.CONSTANT

    // 统一存放字段提供者：单条/批量都视为 () -> List<FieldSpec>，build 时 flatMap 展开
    private val extraFields = mutableListOf<() -> List<FieldSpec>>()

    fun id(id: String) = apply { this.id = id }
    fun factory(factory: RuleFactory<T>) = apply { this.factory = factory }
    fun metadata(metadata: RuleMetadata) = apply { this.metadata = metadata }
    fun metadata(name: String, desc: String? = null) = apply { this.metadata = RuleMetadata(name, desc) }

    // ARCH-UNSETTLED(score-effect, U-005): scoreEffectType() 目前只声明类型，是否应直接接受 ScoreEffect 对象以支持预设值 | next: 评估工厂模式下预设 ScoreEffect 的需求场景
    fun scoreEffectType(type: ScoreEffectType) = apply { this.scoreEffectType = type }

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
            scoreEffectType = scoreEffectType,
            // [完全延迟解析]：所有字段的反射在前端拉取表单时才真正执行
            lazyFieldsResolver = {
                FieldParser.parse(parameterType) + snapshot.flatMap { it() }
            }
        )
    }
}





