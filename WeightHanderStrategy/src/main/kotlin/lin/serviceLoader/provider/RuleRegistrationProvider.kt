package lin.serviceLoader.provider

import lin.rule.build.RuleRegistration

/**
 * rule提供
 */
interface RuleRegistrationProvider {
    fun getRuleRegistrations(): Collection<RuleRegistration<*>>
}