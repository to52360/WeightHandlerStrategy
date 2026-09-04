package lin.ui.combo_plan

import javafx.beans.property.ReadOnlyObjectProperty
import javafx.beans.property.SimpleObjectProperty
import lin.bean.usePlan.ComboPlanDefinition
import lin.bean.usePlan.ComboRelation
import lin.repository.card_group.CardGroupService
import lin.repository.combo_plan.ComboPlanDefinitionEntity
import lin.repository.combo_plan.ComboPlanDefinitionRepository
import lin.ui.card_group.ActiveManagerHolder
import lin.utils.nextShortId

/**
 * Combo 编辑器表单快照：一次性承载「编辑器 → 保存」链路的全部字段。
 *
 * 存在意义：表单字段已达 9 项，若继续用平铺参数回调，新增一个字段就要改三处签名。
 * 新增字段只在此处加一项，Editor 组装、Workbench 转发、Store 消费三处自动跟随。
 */
data class ComboPlanFormSnapshot(
    val managerId: String,
    val id: String?,
    val coreGroupIds: Set<String>,
    val depGroupIds: Set<String>,
    val score: Double,
    /** 起手换牌专用组合协同加分：核心组与依赖组同时保留时给保留子集加此分；0.0 = 不加成 */
    val changeScore: Double = 0.0,
    val relation: ComboRelation,
    val coreMutex: Boolean,
    val mustAdjacent: Boolean
)

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
    fun savePlan(form: ComboPlanFormSnapshot) {
        val finalId = form.id?.trim()?.takeIf { it.isNotEmpty() } ?: nextShortId()

        val entity = ComboPlanDefinitionEntity(
            managerId = form.managerId,
            id = finalId,
            coreGroupIds = form.coreGroupIds.joinToString(","),
            depGroupIds = form.depGroupIds.joinToString(","),
            score = form.score,
            changeScore = form.changeScore,
            coreMutex = form.coreMutex,
            relation = form.relation.name,
            mustAdjacent = form.mustAdjacent
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
