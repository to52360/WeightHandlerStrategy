package lin.rule.defined

import lin.rule.RuleLevel


data class RuleRegistration(
    val ruleId: String,
    val spec: RuleSpec,
    val metadata: RuleMetadata?
)
class RuleMetadata(val name: String, val desc: String)

data class RuleSpec(val ruleId: String, val ruleLevel: RuleLevel, val ruleFactory: RuleFactory)





