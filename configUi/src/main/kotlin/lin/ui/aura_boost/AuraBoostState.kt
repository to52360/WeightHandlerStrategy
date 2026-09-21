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
    /**
     * **新建草稿相位**（显式声明，与 `selectedEntity == null` 各有含义）。
     *
     * ⚠️ 不能靠 `selectedEntity == null` 同时表达「没选中」与「正在新建」——那样工作台必须
     * 另持一个 `isCreatingMode` 标志位消歧，同一事实两处维护即状态冗余（本主题根治对象）。
     */
    val isCreating: Boolean = false,
    val allManagers: List<CardManagerEntity> = emptyList(),
    val conditionTreeMap: Map<String, String> = emptyMap(),
    val searchText: String = "",
    val filterManagerId: String? = null
)
