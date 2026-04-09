package lin.rule.build

import com.fasterxml.jackson.annotation.JsonProperty
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

interface RuleExtraFieldProcessor<T : Any> {
    fun process(parameterType: KClass<T>): List<DynamicField>
}

class JacksonRuleExtraFieldProcessor<T : Any> : RuleExtraFieldProcessor<T> {
    override fun process(parameterType: KClass<T>): List<DynamicField> {
        val ctor = parameterType.primaryConstructor
            ?: error("Rule params must declare a primary constructor: ${parameterType.qualifiedName}")
        val properties = parameterType.memberProperties.associateBy { it.name }

        return ctor.parameters.map { param ->
            val paramName = param.name
                ?: error("Rule params contains unnamed constructor parameter: ${parameterType.qualifiedName}")
            val property = properties[paramName]
            val extra = property?.findAnnotation<ExtraField>() ?: param.findAnnotation<ExtraField>()
            val jsonProperty = property?.findAnnotation<JsonProperty>() ?: param.findAnnotation<JsonProperty>()
            val propertyName = jsonProperty?.value?.takeIf { it.isNotBlank() } ?: paramName

            val fieldType = resolveFieldType(param.type)
            val required = extra?.required ?: !param.type.isMarkedNullable
            val regex = extra?.regex?.takeIf { it.isNotBlank() }
            val dataSource = extra?.dataSource?.takeIf { it.isNotBlank() }
            val options = mergeDeclaredOptions(extra, param.type)

            DynamicField(
                propertyName = propertyName,
                type = fieldType,
                required = required,
                regex = regex,
                options = options,
                dataSource = dataSource
            )
        }
    }

    private fun resolveFieldType(type: KType): DynamicFieldType {
        val classifier = type.classifier as? KClass<*>
            ?: error("Unsupported dynamic field type: $type")
        return when (classifier) {
            Int::class, Long::class, Short::class, Byte::class -> DynamicFieldType.INT
            Boolean::class -> DynamicFieldType.BOOLEAN
            String::class -> DynamicFieldType.STRING
            else -> {
                if (classifier.java.isEnum) DynamicFieldType.STRING
                else error("Unsupported dynamic field type: ${classifier.qualifiedName}")
            }
        }
    }

    /**
     * 合并下拉选项,
     */
    private fun mergeDeclaredOptions(extraField: ExtraField?, type: KType): List<DynamicFieldOption> {
        val enumOptions = enumOptions(type)
        if (extraField == null) return enumOptions

        val labels = extraField.optionLabels.toList()
        val values = extraField.optionValues.toList()
        if (labels.isEmpty() && values.isEmpty()) return enumOptions

        val merged = when {
            labels.isEmpty() -> values.map { DynamicFieldOption(label = it, value = it) }
            values.isEmpty() -> labels.map { DynamicFieldOption(label = it, value = it) }
            labels.size == values.size -> labels.zip(values).map { (label, value) ->
                DynamicFieldOption(label = label, value = value)
            }

            else -> error(
                "@ExtraField optionLabels/optionValues size mismatch: labels=${labels.size}, values=${values.size}"
            )
        }
        return merged
    }

    private fun enumOptions(type: KType): List<DynamicFieldOption> {
        val classifier = type.classifier as? KClass<*> ?: return emptyList()
        if (!classifier.java.isEnum) return emptyList()
        return classifier.java.enumConstants.map { value ->
            val option = value.toString()
            DynamicFieldOption(label = option, value = option)
        }
    }
}
