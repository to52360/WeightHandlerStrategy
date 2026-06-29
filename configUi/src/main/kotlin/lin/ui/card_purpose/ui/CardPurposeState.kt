package lin.ui.card_purpose.ui

import lin.bean.usePlan.PurposeTagId

data class CardUiItem(
    val cardId: String,
    val name: String,
    val purposeTags: Set<PurposeTagId>,
    val replanAfterUse: Boolean,
    val isDbOnly: Boolean = false, // 是否仅存在于数据库历史中
    val createdDate: String? = null
)

data class CardPurposeState(
    val currentPageCards: List<CardUiItem> = emptyList(),
    val totalCount: Int = 0,
    val currentPage: Int = 1,
    val pageSize: Int = 40,
    val selectedCards: List<CardUiItem> = emptyList(),
    val cardGroupFiles: List<String> = emptyList(),
    val selectedGroupFilter: String? = null,
    val selectedTagFilter: PurposeTagId? = null,
    val selectedDateFilter: String? = null,
    val availableDates: List<String> = emptyList(),
    val searchText: String = ""
)
