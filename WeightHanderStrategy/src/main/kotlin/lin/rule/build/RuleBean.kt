package lin.rule.build

import lin.rule.parse.FieldSpec
import kotlin.reflect.KClass

/**
 * Rule 声明其评分类型，框架据此动态生成 builtInFields 和运行时路径。
 * - [CONSTANT] (默认)：builtInFields = [constantScore, missValue]，运行时走 toGuardScoreRule
 * - [SOURCE]：builtInFields = [scoreEffectType, scoreSourceId, scoreOperatorId, missValue]，运行时走 toGuardScoreRule
 * - [NONE]：builtInFields = 空，运行时走 ruleRegistry.build（factory 全权控制）
 */
enum class ScoreEffectType { CONSTANT, SOURCE, NONE }

data class RuleRegistration<T : Any>(
    val ruleId: String,
    val metadata: RuleMetadata?,
    // 动态可验证元数据能力（输入外貌描述）
    val parameterType: KClass<T>,
    val lazyFieldsResolver: () -> List<FieldSpec>,
    // 真正的逻辑规则创造工厂
    val ruleFactory: RuleFactory<T>,
    val scoreEffectType: ScoreEffectType = ScoreEffectType.CONSTANT
)

data class RuleMetadata(
    val name: String?,
    val desc: String?
)

data class DynamicFieldOption(val label: String, val value: String)
