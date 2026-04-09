package lin.rule.build

import kotlin.reflect.KClass

data class RuleRegistration<T : Any>(
    val ruleId: String,
    val spec: RuleSpec<T>,
    val metadata: RuleMetadata?
)

data class RuleMetadata(
    val name: String?,
    val desc: String?
)

data class DynamicFieldOption(val label: String, val value: String)

data class RuleSpec<T : Any>(
    val parameterType: KClass<T>,
    val ruleFactory: RuleFactory<T>
)
