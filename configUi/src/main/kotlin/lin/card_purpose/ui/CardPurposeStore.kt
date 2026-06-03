package lin.card_purpose.ui

import javafx.beans.property.ReadOnlyObjectProperty
import javafx.beans.property.SimpleObjectProperty
import lin.bean.usePlan.PurposeTag
import lin.card_purpose.db.CardPurposeEntity
import lin.card_purpose.db.CardPurposeRepository
import lin.dao.CardGroupJsonParser

class CardPurposeStore(private val repository: CardPurposeRepository) {

    private val stateProperty = SimpleObjectProperty(CardPurposeState())
    val state: CardPurposeState get() = stateProperty.value

    // 只读属性暴露给 UI 订阅
    fun stateProperty(): ReadOnlyObjectProperty<CardPurposeState> = stateProperty

    // 内存存储卡组和卡牌ID集合的映射关系
    private var cardGroupToIds: Map<String, Set<String>> = emptyMap()

    /**
     * 初始化加载数据
     * 合并本地 .cardgroup 文件和本地 SQLite 数据库中的配置与名称
     */
    fun loadInitialData() {
        val cardGroups = CardGroupJsonParser.loadAllCardGroups()
        val fileNames = CardGroupJsonParser.listAvailableFiles()

        val fileCardsMap = mutableMapOf<String, String>()
        val groupToIdsMap = mutableMapOf<String, Set<String>>()

        cardGroups.forEach { (fileName, config) ->
            val cardIds = config.cards.map { it.cardId }.toSet()
            groupToIdsMap[fileName] = cardIds
            config.cards.forEach { card ->
                fileCardsMap[card.cardId] = card.name
            }
        }
        cardGroupToIds = groupToIdsMap

        val dbEntities = repository.findAll()
        val dbMap = dbEntities.associateBy { it.cardId }

        val allCardIds = (fileCardsMap.keys + dbMap.keys).sorted()
        val allCards = allCardIds.map { cardId ->
            val name = fileCardsMap[cardId] ?: dbMap[cardId]?.name ?: "未知卡牌"
            val dbEntity = dbMap[cardId]
            val purposeTags = dbEntity?.toDomain()?.purposeTags ?: emptySet()
            val replanAfterUse = dbEntity?.replanAfterUse ?: false
            val isDbOnly = cardId !in fileCardsMap
            CardUiItem(cardId, name, purposeTags, replanAfterUse, isDbOnly)
        }

        // 保存原有的过滤参数并刷新过滤列表
        val prevSearch = state.searchText
        val prevGroup = state.selectedGroupFilter
        val prevTag = state.selectedTagFilter
        val prevSelectedIds = state.selectedCards.map { it.cardId }.toSet()

        stateProperty.set(
            CardPurposeState(
                allCards = allCards,
                cardGroupFiles = fileNames,
                searchText = prevSearch,
                selectedGroupFilter = prevGroup,
                selectedTagFilter = prevTag
            )
        )

        // 重新过滤并应用选中状态
        updateFilters(prevSearch, prevGroup, prevTag)
        val newSelected = state.allCards.filter { it.cardId in prevSelectedIds }
        stateProperty.set(state.copy(selectedCards = newSelected))
    }

    /**
     * 更新搜索和过滤条件
     */
    fun updateFilters(searchText: String, groupFilter: String?, tagFilter: PurposeTag?) {
        val filtered = state.allCards.filter { card ->
            // 模糊搜索 ID 或名称
            val matchSearch = searchText.isEmpty() ||
                    card.cardId.contains(searchText, true) ||
                    card.name.contains(searchText, true)

            // 卡组过滤
            val matchGroup = groupFilter == null ||
                    cardGroupToIds[groupFilter]?.contains(card.cardId) == true

            // 用途标签过滤
            val matchTag = tagFilter == null ||
                    card.purposeTags.contains(tagFilter)

            matchSearch && matchGroup && matchTag
        }

        stateProperty.set(
            state.copy(
                filteredCards = filtered,
                searchText = searchText,
                selectedGroupFilter = groupFilter,
                selectedTagFilter = tagFilter
            )
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
     * @param cardIds 需要被修改的卡牌 ID 列表
     * @param tagsToApply 用途标签的修改映射。值意义为：
     *                    - true: 全员强制添加该 Tag
     *                    - false: 全员强制移除该 Tag
     *                    - null: 保持各自原来的状态（即处于半选三态时的默认状态）
     * @param replanAfterUse 使用后重规划设置。若为 null，则保持原本状态
     */
    fun saveCardPurpose(
        cardIds: List<String>,
        tagsToApply: Map<PurposeTag, Boolean?>,
        replanAfterUse: Boolean?
    ) {
        if (cardIds.isEmpty()) return

        cardIds.forEach { cardId ->
            val currentItem = state.allCards.find { it.cardId == cardId } ?: return@forEach

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

            // 保存到 SQLite
            val entity = CardPurposeEntity(
                cardId = cardId,
                name = currentItem.name,
                purposeTags = mergedTags.joinToString(",") { it.name },
                replanAfterUse = mergedReplan
            )
            repository.save(entity)
        }

        // 重新加载数据刷新状态
        val prevSelectedIds = cardIds.toSet()
        loadInitialData()
        // 恢复选中状态
        val newSelected = state.allCards.filter { it.cardId in prevSelectedIds }
        stateProperty.set(state.copy(selectedCards = newSelected))
    }

    /**
     * 手动录入新卡牌
     */
    fun addCustomCard(cardId: String, name: String) {
        val cleanId = cardId.trim()
        val cleanName = name.trim().ifBlank { "自定义卡牌" }
        if (cleanId.isBlank()) return

        val existing = state.allCards.find { it.cardId == cleanId }
        val tagsString = existing?.purposeTags?.joinToString(",") { it.name } ?: ""
        val replan = existing?.replanAfterUse ?: false

        val entity = CardPurposeEntity(
            cardId = cleanId,
            name = cleanName,
            purposeTags = tagsString,
            replanAfterUse = replan
        )
        repository.save(entity)

        // 重新加载并自动选中刚录入的卡牌
        loadInitialData()
        val newSelected = state.allCards.filter { it.cardId == cleanId }
        stateProperty.set(state.copy(selectedCards = newSelected))
    }
}
