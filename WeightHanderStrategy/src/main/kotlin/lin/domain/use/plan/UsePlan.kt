package lin.domain.use.plan

import lin.bean.ComboCard
import lin.bean.usePlan.SelectConstraint
import lin.bean.usePlan.UseConstraint
import lin.bean.usePlan.UseIntent

data class UsePlan(
    val cards: List<ComboCard>,
    val intents: Map<ComboCard, UseIntent>,
    val selectConstraints: List<SelectConstraint>,
    val useConstraints: List<UseConstraint>
)
