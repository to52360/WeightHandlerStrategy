package lin.ui.aura_boost

import javafx.beans.property.ReadOnlyObjectProperty
import javafx.beans.property.SimpleObjectProperty
import lin.repository.aura_boost.AuraBoostConfigService
import lin.repository.aura_boost.AuraBoostEntity
import lin.repository.aura_boost.SaveAuraBoostInput
import lin.repository.card_group.CardGroupService
import lin.repository.condition_tree.ConditionTreeConfigRepository
import lin.ui.card_group.ActiveManagerHolder
import lin.ui.components.state.EditorState

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
        // 新建草稿相位跨刷新保持；刷新期间不回落成「选中了某实体」
        val wasCreating = state.isCreating
        val currentFilterManagerId = state.filterManagerId ?: activeManagerHolder.activeManagerId

        stateProperty.set(
            AuraBoostState(
                allEntities = allEntities,
                allManagers = managers,
                conditionTreeMap = conditionTreeMap,
                isCreating = wasCreating,
                searchText = prevSearchText,
                filterManagerId = currentFilterManagerId
            )
        )

        updateFilters(prevSearchText, currentFilterManagerId)

        val newSelected = if (wasCreating) null else state.allEntities.find { it.id == prevSelectedId }
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
     * 更新当前选中项（选中实体即退出新建草稿相位）。
     */
    fun selectEntity(entity: AuraBoostEntity?) {
        stateProperty.set(state.copy(selectedEntity = entity, isCreating = false))
    }

    /** 进入新建草稿相位（清空选中）。 */
    fun enterCreatingMode() {
        stateProperty.set(state.copy(selectedEntity = null, isCreating = true))
    }

    /**
     * 编辑器状态（**单一事实源**）：标题 / 徽标 / 动作可用性全部由它驱动。
     */
    fun editorState(): EditorState<AuraBoostEntity> {
        val current = state
        val selected = current.selectedEntity
        return when {
            current.isCreating -> EditorState.Creating("新建 AuraBoost 配置")
            selected != null -> EditorState.Editing(
                entity = selected,
                title = "编辑 AuraBoost 配置",
                badge = selected.id
            )
            else -> EditorState.Empty("未选择配置")
        }
    }

    /**
     * 保存 AuraBoost 配置（保存即退出新建草稿相位，并选中落库实体）。
     */
    fun saveEntity(input: SaveAuraBoostInput): String {
        val id = service.save(input)
        stateProperty.set(state.copy(isCreating = false))
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
        stateProperty.set(state.copy(isCreating = false))
        loadInitialData()
        if (wasSelected) {
            stateProperty.set(state.copy(selectedEntity = null))
        }
    }
}
