package lin.ui.aura_boost

import javafx.beans.property.ReadOnlyObjectProperty
import javafx.beans.property.SimpleObjectProperty
import lin.repository.aura_boost.AuraBoostConfigService
import lin.repository.aura_boost.AuraBoostEntity
import lin.repository.aura_boost.SaveAuraBoostInput
import lin.repository.card_group.CardGroupService
import lin.repository.condition_tree.ConditionTreeConfigRepository
import lin.ui.card_group.ActiveManagerHolder

class AuraBoostStore(
    private val service: AuraBoostConfigService,
    private val cardGroupService: CardGroupService,
    private val conditionTreeRepository: ConditionTreeConfigRepository,
    private val activeManagerHolder: ActiveManagerHolder
) {
    private val stateProperty = SimpleObjectProperty(AuraBoostState())
    val state: AuraBoostState get() = stateProperty.value

    fun stateProperty(): ReadOnlyObjectProperty<AuraBoostState> = stateProperty

    /**
     * 加载初始数据：刷新卡组方案列表、条件树索引 mapping、AuraBoost 配置列表
     */
    fun loadInitialData() {
        val managers = cardGroupService.loadAllManagers().sortedBy { it.name }
        val conditionTrees = conditionTreeRepository.findAll()
        val conditionTreeMap = conditionTrees.associate { it.id to (it.name ?: "未命名条件树") }

        val allEntities = service.loadAll()
        val prevSearchText = state.searchText
        val prevSelectedId = state.selectedEntity?.id
        val currentFilterManagerId = state.filterManagerId ?: activeManagerHolder.activeManagerId

        stateProperty.set(
            AuraBoostState(
                allEntities = allEntities,
                allManagers = managers,
                conditionTreeMap = conditionTreeMap,
                searchText = prevSearchText,
                filterManagerId = currentFilterManagerId
            )
        )

        updateFilters(prevSearchText, currentFilterManagerId)

        val newSelected = state.allEntities.find { it.id == prevSelectedId }
        stateProperty.set(state.copy(selectedEntity = newSelected))
    }

    /**
     * 更新搜索过滤条件与卡组方案过滤条件
     */
    fun updateFilters(searchText: String, managerIdFilter: String?) {
        val cleanSearch = searchText.trim()
        val filtered = state.allEntities.filter { entity ->
            // 卡组方案过滤：managerIdFilter == null 表示 "全部卡组方案"
            if (managerIdFilter != null && entity.managerId != managerIdFilter) {
                return@filter false
            }

            if (cleanSearch.isEmpty()) return@filter true

            val condName = state.conditionTreeMap[entity.conditionId] ?: ""
            val targetCondName = state.conditionTreeMap[entity.targetConditionId] ?: ""
            val name = entity.name ?: ""

            entity.id.contains(cleanSearch, ignoreCase = true) ||
                    name.contains(cleanSearch, ignoreCase = true) ||
                    entity.conditionId.contains(cleanSearch, ignoreCase = true) ||
                    condName.contains(cleanSearch, ignoreCase = true) ||
                    entity.targetConditionId.contains(cleanSearch, ignoreCase = true) ||
                    targetCondName.contains(cleanSearch, ignoreCase = true)
        }

        stateProperty.set(
            state.copy(
                filteredEntities = filtered,
                searchText = cleanSearch,
                filterManagerId = managerIdFilter
            )
        )
    }

    /**
     * 更新当前选中项
     */
    fun selectEntity(entity: AuraBoostEntity?) {
        stateProperty.set(state.copy(selectedEntity = entity))
    }

    /**
     * 保存 AuraBoost 配置
     */
    fun saveEntity(input: SaveAuraBoostInput): String {
        val id = service.save(input)
        loadInitialData()
        val newSelected = state.allEntities.find { it.id == id }
        stateProperty.set(state.copy(selectedEntity = newSelected))
        return id
    }

    /**
     * 删除指定 AuraBoost 配置
     */
    fun deleteEntity(id: String) {
        val wasSelected = state.selectedEntity?.id == id
        service.delete(id)
        loadInitialData()
        if (wasSelected) {
            stateProperty.set(state.copy(selectedEntity = null))
        }
    }
}
