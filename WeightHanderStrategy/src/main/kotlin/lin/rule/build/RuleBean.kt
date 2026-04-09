package lin.rule.build

import kotlin.reflect.KClass

data class RuleRegistration<T : Any>(
    val ruleId: String,
    val spec: RuleSpec<T>,
    val metadata: RuleMetadata?
)

data class RuleMetadata(
    val name: String?,
    val desc: String?,
    val dynamicFields: List<DynamicField> = emptyList()
)

enum class DynamicFieldType(val jsonType: String) {
    INT("integer"),
    BOOLEAN("boolean"),
    STRING("string")
}

data class DynamicFieldOption(
    val label: String,
    val value: String
)

data class DynamicField(
    val propertyName: String,
    val type: DynamicFieldType,
    val required: Boolean = true,
    val regex: String? = null,
    val options: List<DynamicFieldOption> = emptyList(),
    val dataSource: String? = null
)

data class RuleSpec<T : Any>(
    val parameterType: KClass<T>,
    val ruleFactory: RuleFactory<T>
)
