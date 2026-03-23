package lin.rule.defined

import lin.myLog
import lin.utils.serviceLoader.ServiceLoaderUtils
import lin.weightHandler.condition.bean.ConditionGroup

data class RuleUiItem(
    val ruleId: String,
    val name: String,
    val desc: String?
)

class RuleRegistry(
    providers: Collection<RuleRegistrationProvider> = ServiceLoaderUtils.getCacheServices(RuleRegistrationProvider::class.java)
) {
    private val registrationsById: Map<String, RuleRegistration>

    init {
        val registrationMap = linkedMapOf<String, RuleRegistration>()
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

    fun all(): List<RuleRegistration> = registrationsById.values.toList()

    fun find(ruleId: String): RuleRegistration? = registrationsById[ruleId]

    fun require(ruleId: String): RuleRegistration {
        return find(ruleId) ?: throw IllegalArgumentException("RuleRegistration not found: ruleId=$ruleId")
    }

    fun uiItems(): List<RuleUiItem> {
        return registrationsById.values.map { registration ->
            val metadata = registration.metadata
            RuleUiItem(
                ruleId = registration.ruleId,
                name = metadata?.name ?: registration.ruleId,
                desc = metadata?.desc
            )
        }
    }

    fun build(ruleId: String, conditionGroup: ConditionGroup): RuleLogic {
        return require(ruleId).spec.ruleFactory(conditionGroup)
    }
}
