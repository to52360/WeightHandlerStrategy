package lin.repository.card_purpose

import lin.bean.usePlan.CardPurpose
import lin.bean.usePlan.PurposeTagId

data class CardPurposeEntity(
    val cardId: String,
    val name: String?,
    val purposeTags: String, // 逗号分隔的 tagId
    val replanAfterUse: Boolean = false,
    val createdDate: String? = null
) {
    fun toDomain(): CardPurpose {
        return CardPurpose(
            purposeTags = parsePurposeTags(purposeTags),
            replanAfterUse = replanAfterUse
        )
    }

    private fun parsePurposeTags(value: String): Set<PurposeTagId> {
        if (value.isEmpty()) return emptySet()
        return value.split(",")
            .map { PurposeTagId(it.trim()) }
            .toSet()
    }
}
