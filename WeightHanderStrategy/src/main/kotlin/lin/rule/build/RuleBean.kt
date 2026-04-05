package lin.rule.build

data class RuleRegistration(
    val ruleId: String,
    val spec: RuleSpec,
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
    val options: List<DynamicFieldOption> = emptyList()
)

data class RuleSpec(
    val ruleFactory: RuleFactory
)
