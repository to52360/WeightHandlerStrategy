package lin.card_purpose.ui

import lin.bean.usePlan.PurposeTagId

data class CardUiItem(
    val cardId: String,
    val name: String,
    val purposeTags: Set<PurposeTagId>,
    val replanAfterUse: Boolean,
    val isDbOnly: Boolean = false // 是否仅存在于数据库历史中
)

data class CardPurposeState(
    val currentPageCards: List<CardUiItem> = emptyList(),
    val totalCount: Int = 0,
    val currentPage: Int = 1,
    val pageSize: Int = 20,
    val selectedCards: List<CardUiItem> = emptyList(),
    val cardGroupFiles: List<String> = emptyList(),
    val selectedGroupFilter: String? = null,
    val selectedTagFilter: PurposeTagId? = null,
    val searchText: String = ""
)
