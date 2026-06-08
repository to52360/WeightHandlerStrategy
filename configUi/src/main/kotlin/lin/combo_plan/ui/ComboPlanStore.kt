package lin.combo_plan.ui

import javafx.beans.property.ReadOnlyObjectProperty
import javafx.beans.property.SimpleObjectProperty
import lin.bean.usePlan.ComboPlanDefinition
import lin.bean.usePlan.ComboRelation
import lin.card_group.db.CardGroupService
import lin.card_group.ui.ActiveManagerHolder
import lin.combo_plan.db.ComboPlanDefinitionEntity
import lin.combo_plan.db.ComboPlanDefinitionRepository
import lin.utils.nextShortId

class ComboPlanStore(
    private val repository: ComboPlanDefinitionRepository,
    private val cardGroupService: CardGroupService,
    private val activeManagerHolder: ActiveManagerHolder
) {

    private val stateProperty = SimpleObjectProperty(ComboPlanState())
    val state: ComboPlanState get() = stateProperty.value

    fun stateProperty(): ReadOnlyObjectProperty<ComboPlanState> = stateProperty

    /**
     * 加载初始数据
     * 根据当前选中卡组筛选 Combo 编排记录；未选中时加载全部
     */
    fun loadInitialData() {
        val managers = cardGroupService.loadAll(onlyEnabled = false).sortedBy { it.name }

        // 建立全局无前缀的绑定 ID 到分组实体的快速索引
        val bindingMap = managers.flatMap { it.bindings }.associateBy { it.id }

        val activeId = activeManagerHolder.activeManagerId
        val allPlans = if (activeId != null) {
            repository.findByManagerId(activeId).map { it.toDomain() }
        } else {
            repository.findAll().map { it.toDomain() }
        }

        val prevSearchText = state.searchText
        val prevSelectedId = state.selectedPlan?.id

        stateProperty.set(
            ComboPlanState(
                allPlans = allPlans,
                allManagers = managers,
                bindingMap = bindingMap,
                searchText = prevSearchText
            )
        )

        updateFilters(prevSearchText)

        val newSelected = state.allPlans.find { it.id == prevSelectedId }
        stateProperty.set(state.copy(selectedPlan = newSelected))
    }

    /**
     * 更新搜索关键词并执行内存模糊检索
     */
    fun updateFilters(searchText: String) {
        val cleanSearch = searchText.trim()
        val filtered = state.allPlans.filter { plan ->
            if (cleanSearch.isEmpty()) return@filter true

            // 支持通过 ID 或核心组/依赖组在当前上下文下的分组名称进行搜索
            val coreNames = plan.coreGroupIds.mapNotNull { state.bindingMap[it]?.name }
            val depNames = plan.depGroupIds.mapNotNull { state.bindingMap[it]?.name }

            plan.id.contains(cleanSearch, true) ||
                    coreNames.any { it.contains(cleanSearch, true) } ||
                    depNames.any { it.contains(cleanSearch, true) }
        }

        stateProperty.set(
            state.copy(
                filteredPlans = filtered,
                searchText = cleanSearch
            )
        )
    }

    /**
     * 更新当前选中项
     */
    fun selectPlan(plan: ComboPlanDefinition?) {
        stateProperty.set(state.copy(selectedPlan = plan))
    }

    /**
     * 保存或新建 Combo 编排配置
     */
    fun savePlan(
        managerId: String,
        id: String?,
        coreGroupIds: Set<String>,
        depGroupIds: Set<String>,
        score: Double,
        coreMutex: Boolean,
        relation: ComboRelation,
        mustAdjacent: Boolean
    ) {
        val finalId = id?.trim()?.takeIf { it.isNotEmpty() } ?: nextShortId()

        val entity = ComboPlanDefinitionEntity(
            managerId = managerId,
            id = finalId,
            coreGroupIds = coreGroupIds.joinToString(","),
            depGroupIds = depGroupIds.joinToString(","),
            score = score,
            coreMutex = coreMutex,
            relation = relation.name,
            mustAdjacent = mustAdjacent
        )

        repository.save(entity)

        loadInitialData()
        val newSelected = state.allPlans.find { it.id == finalId }
        stateProperty.set(state.copy(selectedPlan = newSelected))
    }

    /**
     * 物理删除 Combo 编排
     */
    fun deletePlan(id: String) {
        repository.deleteById(id)

        val wasSelected = state.selectedPlan?.id == id
        loadInitialData()

        if (wasSelected) {
            stateProperty.set(state.copy(selectedPlan = null))
        }
    }
}
