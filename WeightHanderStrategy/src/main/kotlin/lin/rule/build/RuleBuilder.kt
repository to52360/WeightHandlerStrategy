package lin.rule.build

import lin.bean.ComboCard
import lin.domain.WarInfo
import lin.rule.handler.IntentResult
import lin.rule.registry.RuleArgsParser
import lin.rule.tree.RuleConfig

fun interface RuleLogic {
    operator fun invoke(callCard: ComboCard, warInfo: WarInfo): IntentResult
}

typealias RuleFactory<T> = (RuleConfig, T) -> RuleLogic

class RuleBuilder<T : Any>(
    private val argsParser: RuleArgsParser<T>
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

    fun requireField(dynamicField: DynamicField) = apply {
        this.dynamicFields.add(dynamicField)
    }

    fun requireField(
        propertyName: String,
        type: DynamicFieldType,
        required: Boolean = true,
        regex: String? = null,
        options: List<DynamicFieldOption> = emptyList()
    ) = apply {
        this.dynamicFields.add(
            DynamicField(
                propertyName = propertyName,
                type = type,
                required = required,
                regex = regex,
                options = options
            )
        )
    }

    inline fun <reified T : Any> requireField(
        propertyName: String,
        required: Boolean = true,
        regex: String? = null,
        options: List<DynamicFieldOption> = emptyList()
    ) = apply {
        requireField(
            propertyName = propertyName,
            type = fieldTypeOf<T>(),
            required = required,
            regex = regex,
            options = options
        )
    }


    fun build(): RuleRegistration<T> {
        val finalMetadata = metadata?.copy(
            dynamicFields = (metadata?.dynamicFields.orEmpty() + dynamicFields).distinct()
        )
        return RuleRegistration(
            ruleId = id,
            spec = RuleSpec(
                argsParser = argsParser,
                ruleFactory = factory
            ),
            metadata = finalMetadata
        )
    }
}

fun <T : Any> ruleBuilder(argsParser: RuleArgsParser<T>): RuleBuilder<T> {
    return RuleBuilder(argsParser)
}

inline fun <reified T : Any> fieldTypeOf(): DynamicFieldType {
    return when (T::class) {
        Int::class -> DynamicFieldType.INT
        Boolean::class -> DynamicFieldType.BOOLEAN
        String::class -> DynamicFieldType.STRING
        else -> error("Unsupported dynamic field type: ${T::class.qualifiedName}")
    }
}
