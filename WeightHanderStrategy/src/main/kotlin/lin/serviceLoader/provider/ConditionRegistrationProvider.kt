package lin.serviceLoader.provider

import lin.rule.condition.ConditionRegistration

interface ConditionRegistrationProvider {
    fun getConditionRegistrations(): Collection<ConditionRegistration<*>>
}
