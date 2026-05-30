package lin.card_use.db

import lin.bean.usePlan.CardUseConfig
import lin.bean.usePlan.PurposeTag
import lin.bean.usePlan.UseStage
import lin.utils.runCatchingLog

data class CardUseConfigEntity(
    val cardGroupId: String,
    val purposeTags: String, // 逗号分隔
    val stageOverride: String?,
    val replanAfterUse: Boolean,
    val orderWeight: Double = 0.0
) {
    fun toDomain(): CardUseConfig {
        return CardUseConfig(
            purposeTags = parsePurposeTags(purposeTags),
            stageOverride = parseStageOverride(stageOverride),
            replanAfterUse = replanAfterUse,
            orderWeight = orderWeight
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

    private fun parseStageOverride(value: String?): UseStage? {
        return value?.takeIf { it.isNotBlank() }?.let {
            runCatchingLog("解析 UseStage 失败: $it") {
                UseStage.valueOf(it.trim())
            }.getOrNull()
        }
    }
}
