package lin.rule.registry

import lin.rule.build.DynamicField
import lin.rule.build.DynamicFieldType

class RuleConfigValidator {
    fun jsonSchema(fields: List<DynamicField>): Map<String, Any> {
        val properties = linkedMapOf<String, Any>()
        fields.forEach { field ->
            val property = linkedMapOf<String, Any>(
                "type" to field.type.jsonType
            )
            if (!field.regex.isNullOrEmpty()) {
                property["pattern"] = field.regex
            }
            if (field.options.isNotEmpty()) {
                property["enum"] = field.options.map { option ->
                    parseOptionValue(option.value, field.type)
                }
            }
            properties[field.propertyName] = property
        }

        return linkedMapOf(
            "\$schema" to "https://json-schema.org/draft/2020-12/schema",
            "type" to "object",
            "properties" to properties,
            "required" to fields.filter { it.required }.map { it.propertyName },
            "additionalProperties" to true
        )
    }

    fun validate(ruleId: String, fields: List<DynamicField>, args: Map<String, Any>) {
        fields.forEach { field ->
            val raw = args[field.propertyName]
            if (raw == null) {
                require(!field.required) {
                    "Rule(ruleId='$ruleId') build failed: missing required arg '${field.propertyName}'"
                }
                return@forEach
            }

            val normalized = normalizeValue(raw, field.type)
            requireNotNull(normalized) {
                "Rule(ruleId='$ruleId') arg '${field.propertyName}' value '$raw' cannot convert to ${field.type}"
            }

            if (field.options.isNotEmpty()) {
                val allowed = field.options.map { parseOptionValue(it.value, field.type) }.toSet()
                require(allowed.contains(normalized)) {
                    "Rule(ruleId='$ruleId') arg '${field.propertyName}' value '$normalized' not in enum $allowed"
                }
            }

            if (!field.regex.isNullOrEmpty()) {
                val text = normalized.toString()
                require(text.matches(Regex(field.regex))) {
                    "Rule(ruleId='$ruleId') arg '${field.propertyName}' value '$text' does not match regex ${field.regex}"
                }
            }
        }
    }

    private fun normalizeValue(value: Any, type: DynamicFieldType): Any? {
        return when (type) {
            DynamicFieldType.INT -> {
                when (value) {
                    is Number -> value.toInt()
                    is String -> value.toIntOrNull()
                    else -> null
                }
            }

            DynamicFieldType.BOOLEAN -> {
                when (value) {
                    is Boolean -> value
                    is String -> value.toBooleanStrictOrNull()
                    else -> null
                }
            }

            DynamicFieldType.STRING -> value.toString()
        }
    }

    private fun parseOptionValue(raw: String, type: DynamicFieldType): Any {
        return when (type) {
            DynamicFieldType.INT -> raw.toIntOrNull() ?: raw
            DynamicFieldType.BOOLEAN -> raw.toBooleanStrictOrNull() ?: raw
            DynamicFieldType.STRING -> raw
        }
    }
}
