package lin.rule.registry

import lin.myLog
import lin.rule.build.DynamicField
import lin.rule.build.RuleLogic
import lin.rule.build.RuleRegistration
import lin.rule.tree.RuleConfig
import lin.utils.serviceLoader.ServiceLoaderUtils

data class RuleUiItem(
    val ruleId: String,
    val name: String,
    val desc: String?,
    val dynamicFields: List<DynamicField> = emptyList()
)

class RuleRegistry(
    providers: Collection<RuleRegistrationProvider> = ServiceLoaderUtils.getCacheServices(RuleRegistrationProvider::class.java),
    private val validator: RuleConfigValidator = RuleConfigValidator()
) {
    private val registrationsById: Map<String, RuleRegistration<*>>

    init {
        val registrationMap = linkedMapOf<String, RuleRegistration<*>>()
        providers.forEach { provider ->
            provider.getRuleRegistrations().forEach { registration ->
                val previous = registrationMap.putIfAbsent(registration.ruleId, registration)
                require(previous == null) {
                    "Duplicate RuleRegistration ruleId=${registration.ruleId}, provider=${provider::class.java.name}"
                }
            }
        }
        registrationsById = registrationMap
        myLog.info { "loaded RuleRegistration ids=${registrationsById.keys}" }
    }

    fun all(): List<RuleRegistration<*>> = registrationsById.values.toList()

    fun find(ruleId: String): RuleRegistration<*>? = registrationsById[ruleId]

    fun require(ruleId: String): RuleRegistration<*> {
        return find(ruleId) ?: throw IllegalArgumentException("RuleRegistration not found: ruleId=$ruleId")
    }

    fun uiItems(): List<RuleUiItem> {
        return registrationsById.values.map { registration ->
            val metadata = registration.metadata
            RuleUiItem(
                ruleId = registration.ruleId,
                name = metadata?.name ?: registration.ruleId,
                desc = metadata?.desc,
                dynamicFields = metadata?.dynamicFields.orEmpty()
            )
        }
    }

    fun jsonSchema(ruleId: String): Map<String, Any> {
        val registration = require(ruleId)
        val fields = registration.metadata?.dynamicFields.orEmpty()
        return validator.jsonSchema(fields)
    }

    fun build(ruleId: String, ruleConfig: RuleConfig): RuleLogic {
        val registration = require(ruleId)
        validator.validate(ruleId, registration.metadata?.dynamicFields.orEmpty(), ruleConfig.args)
        return buildTyped(registration, ruleConfig)
    }

    fun build(ruleConfig: RuleConfig): RuleLogic {
        return build(ruleConfig.ruleId, ruleConfig)
    }

    @Suppress("UNCHECKED_CAST")
    private fun buildTyped(registration: RuleRegistration<*>, ruleConfig: RuleConfig): RuleLogic {
        val typedRegistration = registration as RuleRegistration<Any>
        val params = mapToRuleArgs(ruleConfig.args, typedRegistration.spec.parameterType)
        return typedRegistration.spec.ruleFactory(ruleConfig, params)
    }
}
