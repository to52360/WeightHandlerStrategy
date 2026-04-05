package lin.rule.registry

import lin.rule.build.RuleRegistration

interface RuleRegistrationProvider {
    fun getRuleRegistrations(): Collection<RuleRegistration>
}
