package lin.rule.build

import lin.bean.ComboCard
import lin.domain.WarInfo
import lin.rule.handler.IntentResult
import lin.rule.tree.RuleConfig
import kotlin.reflect.KClass

typealias RuleLogic = (callCard: ComboCard, warInfo: WarInfo) -> IntentResult

typealias RuleFactory<T> = (RuleConfig, T) -> RuleLogic

class RuleBuilder<T : Any>(
    private val parameterType: KClass<T>
) {
    private lateinit var id: String
    private lateinit var factory: RuleFactory<T>
    private val dynamicFields = mutableListOf<DynamicField>()
    private var metadata: RuleMetadata? = null
        get() {
            if (field == null) field = RuleMetadata(id, id)
            return field!!
        }

    fun id(id: String) = apply { this.id = id }
    fun factory(factory: RuleFactory<T>) = apply { this.factory = factory }
    fun metadata(metadata: RuleMetadata) = apply { this.metadata = metadata }
    fun metadata(name: String, desc: String? = null) = apply { this.metadata = RuleMetadata(name, desc) }

    fun extraField(dynamicField: DynamicField) = apply {
        this.dynamicFields.add(dynamicField)
    }

    fun extraFields(dynamicField: List<DynamicField>) = apply {
        this.dynamicFields.addAll(dynamicField)
    }


    fun build(): RuleRegistration<T> {
        val finalMetadata = metadata?.copy(
            dynamicFields = (metadata?.dynamicFields.orEmpty() + dynamicFields).distinct()
        )
        return RuleRegistration(
            ruleId = id,
            spec = RuleSpec(
                parameterType = parameterType,
                ruleFactory = factory
            ),
            metadata = finalMetadata
        )
    }
}

fun <T : Any> ruleBuilder(
    parameterType: KClass<T>,
    extraFieldProcessor: RuleExtraFieldProcessor<T>,
): RuleBuilder<T> {
    return RuleBuilder(parameterType).extraFields(extraFieldProcessor.process(parameterType))
}

inline fun <reified T : Any> ruleBuilder(
    processor: RuleExtraFieldProcessor<T> = JacksonRuleExtraFieldProcessor()
): RuleBuilder<T> {
    return ruleBuilder(T::class, processor)
}

inline fun <reified T : Any> jacksonRuleBuilder(
    processor: RuleExtraFieldProcessor<T> = JacksonRuleExtraFieldProcessor()
): RuleBuilder<T> {
    return ruleBuilder(T::class, processor)
}

inline fun <reified T : Any> fieldTypeOf(): DynamicFieldType {
    return when (T::class) {
        Int::class -> DynamicFieldType.INT
        Boolean::class -> DynamicFieldType.BOOLEAN
        String::class -> DynamicFieldType.STRING
        else -> error("Unsupported dynamic field type: ${T::class.qualifiedName}")
    }
}
