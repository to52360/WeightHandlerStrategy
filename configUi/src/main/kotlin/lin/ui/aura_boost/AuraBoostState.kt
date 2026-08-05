package lin.ui.aura_boost

import lin.repository.aura_boost.AuraBoostEntity
import lin.repository.card_group.CardManagerEntity

/**
 * AuraBoost (Push 广播评分) 配置 UI 状态。
 */
data class AuraBoostState(
    val allEntities: List<AuraBoostEntity> = emptyList(),
    val filteredEntities: List<AuraBoostEntity> = emptyList(),
    val selectedEntity: AuraBoostEntity? = null,
    val allManagers: List<CardManagerEntity> = emptyList(),
    val conditionTreeMap: Map<String, String> = emptyMap(),
    val searchText: String = "",
    val filterManagerId: String? = null
)
