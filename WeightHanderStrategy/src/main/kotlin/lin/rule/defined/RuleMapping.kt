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

class RuleMetadata(val name: String, val desc: String)

data class RuleSpec(val ruleId: String, val ruleLevel: RuleLevel, val ruleFactory: RuleFactory)





