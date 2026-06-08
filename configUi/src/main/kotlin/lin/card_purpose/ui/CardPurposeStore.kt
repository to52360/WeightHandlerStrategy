package lin.card_purpose.ui

import javafx.beans.property.ReadOnlyObjectProperty
import javafx.beans.property.SimpleObjectProperty
import lin.bean.usePlan.PurposeTagId
import lin.card_purpose.db.CardPurposeEntity
import lin.card_purpose.db.CardPurposeRepository
import lin.dao.CardGroupJsonParser

class CardPurposeStore(private val repository: CardPurposeRepository) {

    private val stateProperty = SimpleObjectProperty(CardPurposeState())
    val state: CardPurposeState get() = stateProperty.value

    // 只读属性暴露给 UI 订阅
    fun stateProperty(): ReadOnlyObjectProperty<CardPurposeState> = stateProperty

    // 缓存启用的卡牌名映射，避免翻页时重复全量扫描 JSON 文件
    private var fileCardsMap: Map<String, String> = emptyMap()

    /**
     * 初始化加载数据
     * 合并本地已启用的 .cardgroup 文件并同步到数据库
     */
    fun loadInitialData() {
        val cardGroups = CardGroupJsonParser.loadAllCardGroups()
        val fileNames = CardGroupJsonParser.listAvailableFiles()

        // 仅筛选出启用的卡组卡牌进行同步
        val enabledCards = cardGroups.filter { it.second.enabled }
            .flatMap { (_, config) -> config.cards }
            .map { CardPurposeEntity(cardId = it.cardId, name = it.name, purposeTags = "") }
            .distinctBy { it.cardId }

        if (enabledCards.isNotEmpty()) {
            repository.syncCards(enabledCards)
        }

        // 更新缓存以供翻页渲染显示名时使用
        fileCardsMap = cardGroups.filter { it.second.enabled }
            .flatMap { it.second.cards }
            .associate { it.cardId to it.name }

        // 重置状态
        stateProperty.set(
            CardPurposeState(
                cardGroupFiles = fileNames,
                currentPage = 1,
                pageSize = state.pageSize,
                searchText = "",
                selectedGroupFilter = null,
                selectedTagFilter = null
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
     * 加载特定页面的数据
     */
    fun loadPage(
        page: Int = state.currentPage,
        searchText: String = state.searchText,
        groupFilter: String? = state.selectedGroupFilter,
        tagFilter: PurposeTagId? = state.selectedTagFilter
    ) {
        val resolvedCardIds = getCardIdsForGroup(groupFilter)

        // 统计当前过滤条件下的数据总数
        val total = repository.count(
            cardIds = resolvedCardIds,
            searchText = searchText,
            tagFilter = tagFilter?.value
        )

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
        val dbEntities = repository.findPaginated(
            cardIds = resolvedCardIds,
            searchText = searchText,
            tagFilter = tagFilter?.value,
            limit = limit,
            offset = offset
        )

        // 结合缓存将实体映射为 UI 数据项
        val currentPageCards = dbEntities.map { entity ->
            val name = fileCardsMap[entity.cardId] ?: entity.name ?: "未知卡牌"
            val purposeTags = entity.toDomain().purposeTags
            val replanAfterUse = entity.replanAfterUse
            val isDbOnly = entity.cardId !in fileCardsMap
            CardUiItem(entity.cardId, name, purposeTags, replanAfterUse, isDbOnly)
        }

        stateProperty.set(
            state.copy(
                currentPageCards = currentPageCards,
                totalCount = total,
                currentPage = targetPage,
                searchText = searchText,
                selectedGroupFilter = groupFilter,
                selectedTagFilter = tagFilter
            )
        )
    }

    /**
     * 更新搜索和过滤条件（重置到第一页）
     */
    fun updateFilters(searchText: String, groupFilter: String?, tagFilter: PurposeTagId?) {
        loadPage(page = 1, searchText = searchText, groupFilter = groupFilter, tagFilter = tagFilter)
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
                replanAfterUse = mergedReplan
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
    fun addCustomCard(cardId: String, name: String) {
        val cleanId = cardId.trim()
        val cleanName = name.trim().ifBlank { "自定义卡牌" }
        if (cleanId.isBlank()) return

        val dbEntity = repository.findByCardId(cleanId)
        val tagsString = dbEntity?.purposeTags ?: ""
        val replan = dbEntity?.replanAfterUse ?: false

        val entity = CardPurposeEntity(
            cardId = cleanId,
            name = cleanName,
            purposeTags = tagsString,
            replanAfterUse = replan
        )
        repository.save(entity)

        // 刷新列表
        loadPage(state.currentPage)

        // 高亮选中新卡牌
        val newSelected = state.currentPageCards.filter { it.cardId == cleanId }
        stateProperty.set(state.copy(selectedCards = newSelected))
    }
}
