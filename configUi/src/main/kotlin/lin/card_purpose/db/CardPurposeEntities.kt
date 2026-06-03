package lin.card_purpose.db

import lin.bean.usePlan.CardPurpose
import lin.bean.usePlan.PurposeTag
import lin.utils.runCatchingLog

data class CardPurposeEntity(
    val cardId: String,
    val name: String?,
    val purposeTags: String, // 逗号分隔
    val replanAfterUse: Boolean = false
) {
    fun toDomain(): CardPurpose {
        return CardPurpose(
            purposeTags = parsePurposeTags(purposeTags),
            replanAfterUse = replanAfterUse
        )
    }

    private fun parsePurposeTags(value: String): Set<PurposeTag> {
        if (value.isEmpty()) return emptySet()
        return value.split(",").mapNotNull {
            runCatchingLog("解析 PurposeTag 失败: $it") {
                PurposeTag.valueOf(it.trim())
            }
                .getOrNull()
        }.toSet()
    }
}
