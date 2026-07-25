package lin.ui.card_group

import lin.bean.usePlan.GroupUseOverride
import lin.bean.usePlan.UseStage
import lin.dao.CardWeightConfig
import lin.domain.MatchState
import lin.repository.card_group.CardManagerEntity
import lin.rule.tree.*

/** 列表项展示模型 */
data class CardManagerItem(val entity: CardManagerEntity?, val isDraft: Boolean = false) {
    override fun toString() =
        if (isDraft) "[未保存] ${entity?.name ?: "新分组方案"}" else (entity?.name ?: "") + " (${entity?.sourceFile})"
}

/**
 * 纯粹的、不可变的状态树
 */
data class WorkbenchState(
    // 维度 1: 整体列表
    val managers: List<CardManagerItem> = emptyList(),
    val selectedManagerItem: CardManagerItem? = null,

    // 维度 2: 当前编辑的 Manager 草稿
    val managerName: String = "",
    val managerSourceFile: String = "",
    val managerEnabled: Boolean = true,
    val currentBindings: List<CardGroupBinding> = emptyList(),

    // 维度 3: 选中的具体 Binding 行
    val selectedBindingIndex: Int? = null,

    // 维度 4: 当前 Binding 对应的卡池与选择状态
    val currentCardPool: List<CardWeightConfig> = emptyList(),
    val selectedCards: Set<String> = emptySet()
)

/** 
 * Action 就是一个接受老状态并返回新状态的纯函数
 */
typealias Action = (WorkbenchState) -> WorkbenchState

/**
 * 所有的状态修改行为（纯函数）
 */
object WorkbenchActions {

    fun setManagers(managers: List<CardManagerItem>): Action = { it.copy(managers = managers) }

    fun selectManager(item: CardManagerItem?, bindings: List<CardGroupBinding>): Action = { state ->
        state.copy(
            selectedManagerItem = item,
            managerName = item?.entity?.name ?: "",
            managerSourceFile = item?.entity?.sourceFile ?: "",
            managerEnabled = item?.entity?.enabled ?: true,
            currentBindings = bindings,
            selectedBindingIndex = null,
            currentCardPool = emptyList(),
            selectedCards = emptySet()
        )
    }

    fun updateManagerInfo(name: String, enabled: Boolean): Action = { state ->
        state.copy(managerName = name, managerEnabled = enabled)
    }

    fun selectBinding(index: Int?, cardPool: List<CardWeightConfig>): Action = { state ->
        val selectedCards = if (index != null && index >= 0 && index < state.currentBindings.size) {
            state.currentBindings[index].cardIds.toSet()
        } else {
            emptySet()
        }
        state.copy(
            selectedBindingIndex = index,
            currentCardPool = cardPool,
            selectedCards = selectedCards
        )
    }

    fun addBinding(binding: CardGroupBinding): Action = { state ->
        state.copy(currentBindings = state.currentBindings + binding)
    }

    fun removeBinding(index: Int): Action = { state ->
        val newList = state.currentBindings.toMutableList()
        if (index in newList.indices) {
            newList.removeAt(index)
        }
        // 如果删除的是当前选中的，清理下面的状态
        if (state.selectedBindingIndex == index) {
            state.copy(
                currentBindings = newList,
                selectedBindingIndex = null,
                currentCardPool = emptyList(),
                selectedCards = emptySet()
            )
        } else {
            state.copy(
                currentBindings = newList,
                selectedBindingIndex = if (state.selectedBindingIndex != null && state.selectedBindingIndex > index)
                    state.selectedBindingIndex - 1 else state.selectedBindingIndex
            )
        }
    }

    fun updateBindingName(index: Int, newName: String): Action = { state ->
        val newList = state.currentBindings.toMutableList()
        if (index in newList.indices) {
            newList[index] = newList[index].copy(name = newName)
        }
        state.copy(currentBindings = newList)
    }

    fun updateBindingStageOverride(index: Int, stage: String?): Action = { state ->
        val newList = state.currentBindings.toMutableList()
        if (index in newList.indices) {
            val oldBinding = newList[index]
            val useStage = stage?.let { runCatching { UseStage.valueOf(it) }.getOrNull() }
            val newOverride = (oldBinding.behaviors.findOverride() ?: GroupUseOverride()).copy(stageOverride = useStage)
            newList[index] = oldBinding.copy(
                behaviors = oldBinding.behaviors.withOverride(if (newOverride.isDefault()) null else newOverride)
            )
        }
        state.copy(currentBindings = newList)
    }

    fun updateBindingReplanAfterUse(index: Int, replan: Boolean?): Action = { state ->
        val newList = state.currentBindings.toMutableList()
        if (index in newList.indices) {
            val oldBinding = newList[index]
            val newOverride = (oldBinding.behaviors.findOverride() ?: GroupUseOverride()).copy(replanAfterUse = replan)
            newList[index] = oldBinding.copy(
                behaviors = oldBinding.behaviors.withOverride(if (newOverride.isDefault()) null else newOverride)
            )
        }
        state.copy(currentBindings = newList)
    }

    fun updateBindingOrderWeight(index: Int, weight: Double): Action = { state ->
        val newList = state.currentBindings.toMutableList()
        if (index in newList.indices) {
            val oldBinding = newList[index]
            val weightVal = if (weight != 0.0) weight else null
            val newOverride = (oldBinding.behaviors.findOverride() ?: GroupUseOverride()).copy(orderWeight = weightVal)
            newList[index] = oldBinding.copy(
                behaviors = oldBinding.behaviors.withOverride(if (newOverride.isDefault()) null else newOverride)
            )
        }
        state.copy(currentBindings = newList)
    }

    fun updateBindingUseAction(index: Int, actionId: String, enabled: Boolean): Action = { state ->
        val newList = state.currentBindings.toMutableList()
        if (index in newList.indices) {
            val old = newList[index]
            val set = old.behaviors.findUseActions().toMutableSet()
            if (enabled) set.add(actionId) else {
                set.remove(actionId)
                // 去掉 RECORD_PLAY 时同时清除 stat_dimensions
                if (actionId == "RECORD_PLAY") {
                    val currentConfig = old.behaviors.findExtraConfig().toMutableMap()
                    currentConfig.remove("stat_dimensions")
                    val useList = set.toList()
                    newList[index] = old.copy(
                        behaviors = old.behaviors.withUseActions(useList, currentConfig)
                    )
                }
            }
            newList[index] = old.copy(behaviors = old.behaviors.withUseActions(set.toList()))
        }
        state.copy(currentBindings = newList)
    }

    fun updateBindingStatDimensions(index: Int, dimensions: List<MatchState.StatDimension>): Action = { state ->
        val newList = state.currentBindings.toMutableList()
        if (index in newList.indices) {
            val old = newList[index]
            val currentActions = old.behaviors.findUseActions()
            val newConfig = old.behaviors.findExtraConfig().toMutableMap()
            if (dimensions.isEmpty()) {
                newConfig.remove("stat_dimensions")
            } else {
                newConfig["stat_dimensions"] = dimensions.map {
                    mapOf("key" to it.key.name, "duration" to it.duration.name)
                }
            }
            newList[index] = old.copy(
                behaviors = old.behaviors.withUseActions(currentActions, newConfig)
            )
        }
        state.copy(currentBindings = newList)
    }

    fun toggleCard(cardId: String, isSelected: Boolean): Action = { state ->
        val newCards = if (isSelected) {
            state.selectedCards + cardId
        } else {
            state.selectedCards - cardId
        }

        // 同步更新 currentBindings 中的数据
        val idx = state.selectedBindingIndex
        val newBindings = if (idx != null && idx in state.currentBindings.indices) {
            val oldBinding = state.currentBindings[idx]
            val newBinding = oldBinding.copy(cardIds = newCards.toList())
            val list = state.currentBindings.toMutableList()
            list[idx] = newBinding
            list
        } else {
            state.currentBindings
        }

        state.copy(selectedCards = newCards, currentBindings = newBindings)
    }
}
