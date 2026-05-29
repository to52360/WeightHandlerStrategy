package lin.card_use.db

import lin.bean.usePlan.CardUseConfig
import lin.bean.usePlan.PurposeTag
import lin.bean.usePlan.UseStage
import lin.bean.usePlan.UseTag

data class CardUseConfigEntity(
    val cardGroupId: String,
    val purposeTags: String, // 逗号分隔
    val tags: String,        // 逗号分隔
    val stageOverride: String?
) {
    fun toDomain(): CardUseConfig {
        return CardUseConfig(
            purposeTags = if (purposeTags.isEmpty()) emptySet() else purposeTags.split(",").mapNotNull {
                try {
                    PurposeTag.valueOf(it.trim())
                } catch (e: Exception) {
                    null
                }
            }.toSet(),
            tags = if (tags.isEmpty()) emptySet() else tags.split(",").mapNotNull {
                try {
                    UseTag.valueOf(it.trim())
                } catch (e: Exception) {
                    null
                }
            }.toSet(),
            stageOverride = stageOverride?.let {
                try {
                    UseStage.valueOf(it.trim())
                } catch (e: Exception) {
                    null
                }
            }
        )
    }
}
