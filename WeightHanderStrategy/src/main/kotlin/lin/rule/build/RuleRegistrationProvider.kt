package lin.rule.build

interface RuleRegistrationProvider {
    fun getRuleRegistrations(): Collection<RuleRegistration>
}
