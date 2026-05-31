package lin.group_use_override.db

import lin.bean.usePlan.GroupUseOverride
import lin.bean.usePlan.UseStage
import lin.utils.runCatchingLog

data class GroupUseOverrideEntity(
    val cardGroupId: String,
    val stageOverride: String?,
    val replanAfterUse: Boolean?,
    val orderWeight: Double = 0.0
) {
    fun toDomain(): GroupUseOverride {
        return GroupUseOverride(
            stageOverride = parseStageOverride(stageOverride),
            replanAfterUse = replanAfterUse,
            orderWeight = orderWeight
        )
    }

    private fun parseStageOverride(value: String?): UseStage? {
        return value?.takeIf { it.isNotBlank() }?.let {
            runCatchingLog("解析 UseStage 失败: $it") {
                UseStage.valueOf(it.trim())
            }.getOrNull()
        }
    }
}
