package lin.domain.use.plan

import lin.bean.ComboCard

data class UsePlan(
    val cards: List<ComboCard>,
    val intents: Map<ComboCard, UseIntent>,
    val selectConstraints: List<SelectConstraint>,
    val useConstraints: List<UseConstraint>
)
