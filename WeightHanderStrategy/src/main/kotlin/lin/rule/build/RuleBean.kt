package lin.rule.build

import lin.rule.parse.RuleFieldSpec
import kotlin.reflect.KClass

data class RuleRegistration<T : Any>(
    val ruleId: String,
    val metadata: RuleMetadata?,
    // 动态可验证元数据能力（输入外貌描述）
    val parameterType: KClass<T>,
    val lazyFieldsResolver: () -> List<RuleFieldSpec>,
    // 真正的逻辑规则创造工厂
    val ruleFactory: RuleFactory<T>
)

data class RuleMetadata(
    val name: String?,
    val desc: String?
)

data class DynamicFieldOption(val label: String, val value: String)
