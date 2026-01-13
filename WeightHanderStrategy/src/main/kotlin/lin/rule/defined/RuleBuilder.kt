package lin.rule.defined

import lin.bean.ComboCard
import lin.domain.WarInfo
import lin.rule.IntentResult
import lin.rule.RuleLevel
import lin.weightHandler.condition.bean.ConditionGroup

fun interface RuleLogic {
    operator fun invoke(callCard: ComboCard, warInfo: WarInfo): IntentResult
}

typealias RuleFactory = (ConditionGroup) -> RuleLogic


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

    fun build(): RuleRegistration {
        return RuleRegistration(
            id,
            spec = RuleSpec(id, level, factory),
            metadata = metadata
        )
    }
}
