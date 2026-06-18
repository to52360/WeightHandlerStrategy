package lin.serviceLoader.provider

import lin.rule.build.RuleRegistration
import lin.rule.condition.ConditionRegistration

/**
 * 条件注册提供者
 */
interface ConditionRegistrationProvider {
    fun getConditionRegistrations(): Collection<ConditionRegistration<*>>
}

/**
 * 规则注册提供者
 */
interface RuleRegistrationProvider {
    fun getRuleRegistrations(): Collection<RuleRegistration<*>>
}
