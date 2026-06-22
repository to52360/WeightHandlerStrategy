package lin.rule.build

import lin.rule.parse.FieldSpec
import lin.rule.score.ScoreEffect
import kotlin.reflect.KClass

/**
 * Rule 声明其默认评分效应，框架据此动态生成 builtInFields。
 * 所有 Rule.Coded 命中分统一走 scoreEffect（NONE 方案已移除），
 * factory 负责副作用/剪枝，factory 可通过 leafConfig.scoreEffect 获取命中分。
 */
data class RuleRegistration<T : Any>(
    val ruleId: String,
    val metadata: RuleMetadata?,
    // 动态可验证元数据能力（输入外貌描述）
    val parameterType: KClass<T>,
    val lazyFieldsResolver: () -> List<FieldSpec>,
    // 真正的逻辑规则创造工厂
    val ruleFactory: RuleFactory<T>,
    val defaultScoreEffect: ScoreEffect = ScoreEffect.ConstantScore(0.0)
)

data class RuleMetadata(
    val name: String?,
    val desc: String?
)

data class DynamicFieldOption(val label: String, val value: String)
