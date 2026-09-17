package lin.ui.card_group

import javafx.beans.property.SimpleObjectProperty
import lin.bean.usePlan.ConditionalStageOverride
import lin.dao.CardGroupJsonParser
import lin.domain.MatchState
import lin.repository.card_group.*
import lin.rule.tree.CardGroupBinding
import lin.rule.tree.GroupMembership
import lin.ui.service.PresetCatalogLoader
import java.util.*

class WorkbenchStore(
    private val service: CardGroupService,
    private val presetService: StrategyPresetService,
    private val catalogLoader: PresetCatalogLoader,
    /** T-TG-035：UI 删除走**与 MCP 同一份级联编排**（不落快照）。 */
    private val cascadeDeleteService: CardGroupCascadeDeleteService
) {

    val stateProperty = SimpleObjectProperty(WorkbenchState())
    var state: WorkbenchState
        get() = stateProperty.get()
        private set(value) = stateProperty.set(value)

    fun dispatch(action: Action) {
        state = action(state)
    }

    // ==========================================
    // 包含副作用的复杂操作 (类似 Redux Thunk / MVI Intent)
    // ==========================================

    fun loadInitialData() {
        val managers = service.loadAllManagers().map { CardManagerItem(it) }
        dispatch(WorkbenchActions.setManagers(managers))

        // T-TG-024: 预设域目录单点装配（列表 + 候选树 + 时序规则 + 用途全集）
        val catalog = catalogLoader.load()
        dispatch(
            WorkbenchActions.setPresetsAndUniverse(
                catalog.presets,
                catalog.candidateTrees,
                catalog.timingRules,
                catalog.purposeUniverse,
                catalog.tagDisplayNames
            )
        )
    }

    fun selectManager(item: CardManagerItem?) {
        if (item == null) {
            dispatch(WorkbenchActions.selectManager(null, emptyList()))
            return
        }

        val presetDetail = item.entity?.presetId?.let { presetService.findDetail(it) }
        val deckDelta = item.entity?.id?.let { presetService.findDeckDelta(it) }

        if (item.isDraft) {
            // 如果是草稿，不用查 DB
            dispatch(WorkbenchActions.selectManager(item, emptyList(), presetDetail, deckDelta))
        } else {
            // 查 DB 获取 Bindings
            val bindings = service.loadBindings(item.entity!!.id)
            dispatch(WorkbenchActions.selectManager(item, bindings, presetDetail, deckDelta))
        }
    }

    fun createNewManager(sourceFile: String, name: String) {
        val draftEntity = CardManagerEntity(
            id = UUID.randomUUID().toString().substring(0, 8),
            name = name,
            sourceFile = sourceFile,
            enabled = true
        )
        val newItem = CardManagerItem(draftEntity, isDraft = true)
        val newManagers = listOf(newItem) + state.managers
        dispatch(WorkbenchActions.setManagers(newManagers))
        selectManager(newItem)
    }

    /**
     * 卡组切换/关联策略预设（T-TG-018）。
     * [presetId] null = 不用预设
     */
    fun setCardGroupPreset(presetId: String?): String? {
        val currentItem = state.selectedManagerItem ?: return "未选择卡组"
        val targetPresetId = presetId?.takeIf { it.isNotBlank() }

        if (currentItem.isDraft || currentItem.entity == null) {
            // 草稿卡组，只暂存
            val detail = targetPresetId?.let { presetService.findDetail(it) }
            dispatch(WorkbenchActions.updateManagerPreset(targetPresetId, detail))
            return null
        }

        // 已落库卡组，调用 service 更新
        val error = presetService.setCardGroupPreset(currentItem.entity.id, targetPresetId)
        if (error != null) return error

        val detail = targetPresetId?.let { presetService.findDetail(it) }
        dispatch(WorkbenchActions.updateManagerPreset(targetPresetId, detail))

        // 重新拉取列表以刷新引用关系和 presetId
        val managers = service.loadAllManagers().map { CardManagerItem(it) }
        dispatch(WorkbenchActions.setManagers(managers))
        val updatedCandidate = managers.find { it.entity?.id == currentItem.entity.id }
        dispatch(
            WorkbenchActions.selectManager(
                updatedCandidate,
                state.currentBindings,
                detail,
                state.currentDeckDelta
            )
        )
        return null
    }

    /**
     * 保存卡组微调层增量项（T-TG-018；T-TG-029 起含独立的惜售维度；含「不使用用途」去除通道；
     * T-TG-041 起支持**维度级排除**）。
     */
    fun saveDeckDelta(
        treeExclusions: Map<String, Collection<String>>?,
        timings: Map<String, TimingOverride>?,
        surplus: Map<String, SurplusOverride>? = null,
        excludedPurposes: Set<String>? = null,
        exclusionDimensions: Map<String, Set<String>>? = null
    ): String? {
        val currentItem = state.selectedManagerItem ?: return "未选择卡组"
        val managerId = currentItem.entity?.id ?: return "卡组尚未保存"

        val error = presetService.saveDeckDelta(
            managerId,
            treeExclusions,
            timings,
            surplus,
            excludedPurposes,
            exclusionDimensions
        )
        if (error != null) return error

        val updatedDelta = presetService.findDeckDelta(managerId)
        dispatch(WorkbenchActions.updateDeckDelta(updatedDelta))
        return null
    }

    fun saveCurrentManager() {
        val currentItem = state.selectedManagerItem ?: return

        // 收集数据并保存
        val id = service.saveManager(
            ManagerSaveCommand(
                name = state.managerName,
                sourceFile = state.managerSourceFile,
                enabled = state.managerEnabled,
                bindings = state.currentBindings,
                existingId = if (currentItem.isDraft) null else currentItem.entity?.id
            )
        )

        // 如果是草稿，且预设了 presetId，保存后关联预设
        if (currentItem.isDraft && state.managerPresetId != null) {
            presetService.setCardGroupPreset(id, state.managerPresetId)
        }

        // 保存后重新加载整体列表
        loadInitialData()

        // 尝试重新选中刚刚保存的项
        val newManager = state.managers.find { it.entity?.id == id }
        selectManager(newManager)
    }

    /**
     * 删除当前卡组方案。
     *
     * T-TG-035 前：只删绑定 / 行为 / manager，**评估树与卡组维度项等从属资源全留孤儿**
     * （比 MCP `delete(resource=card_group)` 漏得多）⇒ 现统一走级联编排（仍不落快照，见 `D-TG-016`）。
     */
    fun deleteCurrentManager() {
        val currentItem = state.selectedManagerItem ?: return
        if (!currentItem.isDraft && currentItem.entity != null) {
            cascadeDeleteService.cascadeDelete(currentItem.entity.id)
        }
        loadInitialData()
        selectManager(null)
    }

    fun selectBinding(index: Int?) {
        if (index == null || index < 0 || index >= state.currentBindings.size) {
            dispatch(WorkbenchActions.selectBinding(null, emptyList()))
            return
        }

        // 副作用：从文件加载全量卡池 (使用 Manager 级别的 sourceFile)
        val cardPool = CardGroupJsonParser.loadByFileName(state.managerSourceFile)?.cards ?: emptyList()
        dispatch(WorkbenchActions.selectBinding(index, cardPool))
    }

    fun addBinding() {
        val nextNum = state.currentBindings.size + 1
        val binding = CardGroupBinding(
            id = UUID.randomUUID().toString().substring(0, 8),
            managerId = state.selectedManagerItem?.entity?.id ?: "",
            name = "分组 $nextNum",
            membership = GroupMembership.Static(emptyList())
        )
        dispatch(WorkbenchActions.addBinding(binding))
    }

    /**
     * T-007：快速建组入口——新建一个谓词组（成员由条件树运行时判定，无显式卡列表）。
     * [includeDerived] null = 未声明，回落卡组级 defaultIncludeDerived（再回落 false）。
     */
    fun addPredicateBinding(name: String, conditionId: String, includeDerived: Boolean?) {
        val binding = CardGroupBinding(
            id = UUID.randomUUID().toString().substring(0, 8),
            managerId = state.selectedManagerItem?.entity?.id ?: "",
            name = name,
            membership = GroupMembership.Predicate(conditionId = conditionId, includeDerived = includeDerived)
        )
        dispatch(WorkbenchActions.addBinding(binding))
        // 建组后自动选中新组（谓词组选中态会禁用选卡区并提示条件来源）
        selectBinding(state.currentBindings.lastIndex)
    }

    fun updateBindingName(newName: String) {
        val idx = state.selectedBindingIndex ?: return
        dispatch(WorkbenchActions.updateBindingName(idx, newName))
    }

    fun updateBindingStageOverride(stage: String?) {
        val idx = state.selectedBindingIndex ?: return
        dispatch(WorkbenchActions.updateBindingStageOverride(idx, stage))
    }

    fun updateBindingReplanAfterUse(replan: Boolean?) {
        val idx = state.selectedBindingIndex ?: return
        dispatch(WorkbenchActions.updateBindingReplanAfterUse(idx, replan))
    }

    fun updateBindingOrderWeight(weight: Double) {
        val idx = state.selectedBindingIndex ?: return
        dispatch(WorkbenchActions.updateBindingOrderWeight(idx, weight))
    }

    fun updateBindingConditionalStage(conditionalStage: ConditionalStageOverride?) {
        val idx = state.selectedBindingIndex ?: return
        dispatch(WorkbenchActions.updateBindingConditionalStage(idx, conditionalStage))
    }

    fun updateBindingUseAction(actionId: String, enabled: Boolean) {
        val idx = state.selectedBindingIndex ?: return
        dispatch(WorkbenchActions.updateBindingUseAction(idx, actionId, enabled))
    }

    fun updateBindingStatDimensions(dimensions: List<MatchState.StatDimension>) {
        val idx = state.selectedBindingIndex ?: return
        dispatch(WorkbenchActions.updateBindingStatDimensions(idx, dimensions))
    }

    fun updateBindingSurplusGate(idleThreshold: Int?) {
        val idx = state.selectedBindingIndex ?: return
        dispatch(WorkbenchActions.updateBindingSurplusGate(idx, idleThreshold))
    }
}
