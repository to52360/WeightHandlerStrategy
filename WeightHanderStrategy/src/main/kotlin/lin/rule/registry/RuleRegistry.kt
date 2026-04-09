package lin.rule.registry

import lin.myLog
import lin.rule.build.RuleLogic
import lin.rule.build.RuleRegistration
import lin.rule.tree.RuleConfig
import lin.utils.serviceLoader.ServiceLoaderUtils



class RuleRegistry(
    providers: Collection<RuleRegistrationProvider> = ServiceLoaderUtils.getCacheServices(RuleRegistrationProvider::class.java),
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

    fun uiItems() {
        TODO()
    }


    fun build(ruleConfig: RuleConfig): RuleLogic {
        TODO()
    }


}
