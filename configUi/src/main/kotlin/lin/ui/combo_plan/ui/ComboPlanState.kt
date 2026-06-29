package lin.ui.combo_plan.ui

import lin.bean.usePlan.ComboPlanDefinition
import lin.rule.tree.CardGroupBinding
import lin.rule.tree.CardGroupManagerConfig

data class ComboPlanState(
    val allPlans: List<ComboPlanDefinition> = emptyList(),
    val filteredPlans: List<ComboPlanDefinition> = emptyList(),
    val selectedPlan: ComboPlanDefinition? = null,
    val allManagers: List<CardGroupManagerConfig> = emptyList(),
    val bindingMap: Map<String, CardGroupBinding> = emptyMap(),
    val searchText: String = ""
)
