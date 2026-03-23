package lin.rule.defined

interface RuleRegistrationProvider {
    fun getRuleRegistrations(): Collection<RuleRegistration>
}
