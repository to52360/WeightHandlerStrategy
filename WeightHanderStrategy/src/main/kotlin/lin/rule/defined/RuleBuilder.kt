package lin.rule.defined

import lin.rule.RuleLevel
import lin.weightHandler.condition.bean.ConditionGroup

data class RuleRegistration(
    val ruleId: String,
    val spec: RuleSpec,
    val metadata: RuleMetadata?
)


class RuleBuilder {
    private lateinit var id: String
    private var level = RuleLevel.DEF
    private lateinit var factory: RuleFactory
    private var metadata: RuleMetadata? = null

    fun id(id: String) = apply { this.id = id }
    fun level(level: RuleLevel) = apply { this.level = level }
    fun factory(factory: RuleFactory) = apply { this.factory = factory }
    fun metadata(metadata: RuleMetadata) = apply { this.metadata = metadata }
    fun metadata(name: String, desc: String? = null) = apply { this.metadata = RuleMetadata(name, desc ?: name) }
    fun <T : Any> createRuleLogic(toT: (ConditionGroup) -> T, extRuleFactory: (ConditionGroup, T) -> RuleLogic) =
        apply {
            factory = { conditionGroup ->
                val extStatus = toT(conditionGroup)
                extRuleFactory(conditionGroup, extStatus)
            }
        }

    fun build(): RuleRegistration {
        return RuleRegistration(
            id,
            spec = RuleSpec(id, level, factory),
            metadata = metadata
        )
    }
}