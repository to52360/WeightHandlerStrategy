package lin.ui.card_purpose

import javafx.beans.property.ReadOnlyObjectProperty
import javafx.beans.property.SimpleObjectProperty
import lin.bean.usePlan.PurposeTagId
import lin.dao.CardGroupJsonParser
import lin.repository.HsCardRepository
import lin.repository.card_purpose.CardPurposeEntity
import lin.repository.card_purpose.CardPurposeRepository
import java.time.LocalDate

class CardPurposeStore(
    private val repository: CardPurposeRepository,
    private val hsCardRepo: HsCardRepository
) {

    private val stateProperty = SimpleObjectProperty(CardPurposeState())
    val state: CardPurposeState get() = stateProperty.value

    // 只读属性暴露给 UI 订阅
    fun stateProperty(): ReadOnlyObjectProperty<CardPurposeState> = stateProperty

    /**
     * 初始化加载数据
     * 仅从数据库加载已录入的日期和基础配置，不做任何 JSON 文件的全量读取
     */
    fun loadInitialData() {
        val fileNames = CardGroupJsonParser.listAvailableFiles()
        val availableDates = repository.getAvailableDates()

        // 重置状态
        stateProperty.set(
            CardPurposeState(
                cardGroupFiles = fileNames,
                availableDates = availableDates,
                currentPage = 1,
                pageSize = state.pageSize,
                searchText = "",
                selectedGroupFilter = null,
                selectedTagFilter = null,
                selectedDateFilter = null
            )
        )

        // 加载第一页
        loadPage(1)
    }

    private fun getCardIdsForGroup(groupName: String?): Set<String>? {
        if (groupName == null) return null
        val config = CardGroupJsonParser.loadByFileName(groupName) ?: return emptySet()
        return config.cards.map { it.cardId }.toSet()
    }

    /**
     * 加载特定页面的数据，支持文本搜索、卡组过滤、标签过滤、日期过滤
     */
    fun loadPage(
        page: Int = state.currentPage,
        searchText: String = state.searchText,
        groupFilter: String? = state.selectedGroupFilter,
        tagFilter: PurposeTagId? = state.selectedTagFilter,
        dateFilter: String? = state.selectedDateFilter
    ) {
        val resolvedCardIds = getCardIdsForGroup(groupFilter)
        val tagFilterStr = tagFilter?.value

        // 分支查询总数
        val total = if (resolvedCardIds != null) {
            repository.countConfig(resolvedCardIds, searchText, tagFilterStr, dateFilter)
        } else {
            repository.countView(searchText, tagFilterStr, dateFilter)
        }

        // 修正目标页码范围
        val limit = state.pageSize
        val totalPages = (total + limit - 1) / limit
        val targetPage = if (page > totalPages) {
            if (totalPages > 0) totalPages else 1
        } else if (page < 1) {
            1
        } else {
            page
        }

        val offset = (targetPage - 1) * limit

        // 分支查询数据
        val dbEntities = if (resolvedCardIds != null) {
            repository.findConfigPage(resolvedCardIds, searchText, tagFilterStr, dateFilter, limit, offset)
        } else {
            repository.findViewPage(searchText, tagFilterStr, dateFilter, limit, offset)
        }

        // 直接转换为 UI 展示项
        val currentPageCards = dbEntities.map { entity ->
            CardUiItem(
                cardId = entity.cardId,
                name = entity.name ?: "未知卡牌",
                purposeTags = entity.toDomain().purposeTags,
                replanAfterUse = entity.replanAfterUse,
                createdDate = entity.createdDate,
                isDbOnly = false
            )
        }

        stateProperty.set(
            state.copy(
                currentPageCards = currentPageCards,
                totalCount = total,
                currentPage = targetPage,
                searchText = searchText,
                selectedGroupFilter = groupFilter,
                selectedTagFilter = tagFilter,
                selectedDateFilter = dateFilter
            )
        )
    }

    /**
     * 更新搜索和过滤条件（重置到第一页）
     */
    fun updateFilters(
        searchText: String,
        groupFilter: String?,
        tagFilter: PurposeTagId?,
        dateFilter: String? = state.selectedDateFilter
    ) {
        loadPage(
            page = 1,
            searchText = searchText,
            groupFilter = groupFilter,
            tagFilter = tagFilter,
            dateFilter = dateFilter
        )
    }

    /**
     * 更新当前选中项
     */
    fun selectCards(cards: List<CardUiItem>) {
        stateProperty.set(state.copy(selectedCards = cards))
    }

    /**
     * 批量或单卡保存卡牌战略用途配置
     */
    fun saveCardPurpose(
        cardIds: List<String>,
        tagsToApply: Map<PurposeTagId, Boolean?>,
        replanAfterUse: Boolean?
    ) {
        if (cardIds.isEmpty()) return

        val entitiesToSave = mutableListOf<CardPurposeEntity>()

        cardIds.forEach { cardId ->
            val currentItem = state.currentPageCards.find { it.cardId == cardId } ?: return@forEach

            // 合并 Tag
            val mergedTags = currentItem.purposeTags.toMutableSet()
            tagsToApply.forEach { (tag, apply) ->
                when (apply) {
                    true -> mergedTags.add(tag)
                    false -> mergedTags.remove(tag)
                    null -> { /* 半选态保留原值 */
                    }
                }
            }

            // 合并重规划
            val mergedReplan = replanAfterUse ?: currentItem.replanAfterUse

            val entity = CardPurposeEntity(
                cardId = cardId,
                name = currentItem.name,
                purposeTags = mergedTags.joinToString(",") { it.value },
                replanAfterUse = mergedReplan,
                createdDate = currentItem.createdDate ?: LocalDate.now().toString()
            )
            entitiesToSave.add(entity)
        }

        if (entitiesToSave.isNotEmpty()) {
            repository.saveAll(entitiesToSave)
        }

        // 重新拉取当前分页的数据以同步最新状态
        loadPage(state.currentPage)

        // 恢复选中状态
        val newSelected = state.currentPageCards.filter { it.cardId in cardIds }
        stateProperty.set(state.copy(selectedCards = newSelected))
    }

    /**
     * 手动录入新卡牌
     */
    fun addCustomCard(cardId: String) {
        val cleanId = cardId.trim()
        if (cleanId.isBlank()) return

        val name = hsCardRepo.findName(cleanId) ?: "未知卡牌"

        val dbEntity = repository.findByCardId(cleanId)
        val tagsString = dbEntity?.purposeTags ?: ""
        val replan = dbEntity?.replanAfterUse ?: false
        val currentDate = dbEntity?.createdDate ?: LocalDate.now().toString()

        val entity = CardPurposeEntity(
            cardId = cleanId,
            name = name,
            purposeTags = tagsString,
            replanAfterUse = replan,
            createdDate = currentDate
        )
        repository.save(entity)

        // 刷新列表与日期列表
        val availableDates = repository.getAvailableDates()
        stateProperty.set(state.copy(availableDates = availableDates))

        // 重新加载当前页
        loadPage(state.currentPage)

        // 高亮选中新卡牌
        val newSelected = state.currentPageCards.filter { it.cardId == cleanId }
        stateProperty.set(state.copy(selectedCards = newSelected))
    }
}
