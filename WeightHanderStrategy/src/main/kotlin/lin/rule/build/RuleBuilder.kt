package lin.rule.build

import lin.bean.ComboCard
import lin.domain.WarInfo
import lin.rule.handler.IntentResult
import lin.rule.tree.RuleConfig

fun interface RuleLogic {
    operator fun invoke(callCard: ComboCard, warInfo: WarInfo): IntentResult
}

typealias RuleFactory = (RuleConfig) -> RuleLogic

class RuleBuilder {
    private lateinit var id: String
    private lateinit var factory: RuleFactory
    private val dynamicFields = mutableListOf<DynamicField>()
    private var metadata: RuleMetadata? = null
        get() {
            //没设置就用id作为名字
            if (field == null) field = RuleMetadata(id, id)
            return field!!
        }
    fun id(id: String) = apply { this.id = id }
    fun factory(factory: RuleFactory) = apply { this.factory = factory }
    fun metadata(metadata: RuleMetadata) = apply { this.metadata = metadata }
    fun metadata(name: String, desc: String? = null) = apply { this.metadata = RuleMetadata(name, desc) }

    fun requireField(propertyName: String, type: Class<*>, regex: String? = null) = apply {
        this.dynamicFields.add(DynamicField(propertyName, type, regex))
    }

    fun requireIntField(propertyName: String, regex: String? = null) = apply {
        requireField(propertyName, Int::class.java, regex)
    }

    fun requireBooleanField(propertyName: String) = apply {
        requireField(propertyName, Boolean::class.java)
    }

    fun requireStringField(propertyName: String, regex: String? = null) = apply {
        requireField(propertyName, String::class.java, regex)
    }

    fun build(): RuleRegistration {
        val finalMetadata = metadata?.copy(
            dynamicFields = (metadata?.dynamicFields.orEmpty() + dynamicFields).distinct()
        )
        return RuleRegistration(
            ruleId = id,
            spec = RuleSpec(
                ruleFactory = factory
            ),
            metadata = finalMetadata
        )
    }
}
