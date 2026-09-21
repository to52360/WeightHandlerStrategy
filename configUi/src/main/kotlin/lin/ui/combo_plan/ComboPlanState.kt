package lin.ui.combo_plan

import lin.bean.usePlan.ComboPlanDefinition
import lin.rule.tree.CardGroupBinding
import lin.rule.tree.CardGroupManagerConfig

data class ComboPlanState(
    val allPlans: List<ComboPlanDefinition> = emptyList(),
    val filteredPlans: List<ComboPlanDefinition> = emptyList(),
    val selectedPlan: ComboPlanDefinition? = null,
    /**
     * **新建草稿相位**（显式声明，与 `selectedPlan == null` 各有含义）。
     *
     * ⚠️ 不能靠 `selectedPlan == null` 同时表达「没选中」与「正在新建」——那样工作台必须
     * 另持一个 `isCreatingMode` 标志位消歧（同一事实两处维护，本主题根治对象，见 `K-DC-005`）。
     */
    val isCreating: Boolean = false,
    val allManagers: List<CardGroupManagerConfig> = emptyList(),
    val bindingMap: Map<String, CardGroupBinding> = emptyMap(),
    val searchText: String = ""
)
