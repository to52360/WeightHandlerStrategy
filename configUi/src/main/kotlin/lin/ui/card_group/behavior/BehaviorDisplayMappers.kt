package lin.ui.card_group.behavior

import lin.bean.usePlan.UseStage
import lin.domain.MatchState

/**
 * 行为编辑器 UI 中文化显示与底层 ID/Enum 双向映射工具类。
 */
object BehaviorDisplayMappers {

    // ── 出牌阶段 (UseStage) ──
    // Q-032：阶段已改用时时序中性命名，显示名同步为「时序位置」而非「用途」。
    // 原因：原显示名（资源/铺场/解场/防御/斩杀）照搬用途，导致「过牌牌放进解场阶段」读来自相矛盾。
    // 阶段只回答「大概什么时候出」，不回答「这牌是干什么用的」。
    private val stageLabelMap = mapOf(
        UseStage.FIRST to "最先 (FIRST)",
        UseStage.SETUP to "铺垫 (SETUP)",
        UseStage.MID to "中段 (MID)",
        UseStage.LATE to "中后段 (LATE)",
        UseStage.GENERAL to "常规 (GENERAL)",
        UseStage.LAST to "压轴 (LAST)"
    )
    private val labelToStageNameMap = stageLabelMap.entries.associate { (stage, label) -> label to stage.name }

    fun stageToLabel(stageName: String?): String {
        if (stageName == null) return "(不覆盖)"
        val enumValue = try {
            UseStage.valueOf(stageName)
        } catch (_: Exception) {
            null
        }
        return stageLabelMap[enumValue] ?: stageName
    }

    fun labelToStageName(label: String?): String? {
        if (label == null || label == "(不覆盖)") return null
        return labelToStageNameMap[label] ?: label
    }

    fun allStageLabels(): List<String> = listOf("(不覆盖)") + UseStage.entries.map { stageToLabel(it.name) }

    // ── 使用动作 (UseAction) ──
    private val actionLabelMap = mapOf(
        "RECORD_PLAY" to "记录打出统计 (RECORD_PLAY)",
        "AWAIT_ANIMATION" to "等待动画结束 (AWAIT_ANIMATION)"
    )

    fun actionToLabel(actionId: String): String = actionLabelMap[actionId] ?: actionId

    // ── 统计周期 (StatDuration) ──
    private val durationLabelMap = mapOf(
        MatchState.StatDuration.GAME to "整局累计 (GAME)",
        MatchState.StatDuration.ROUND to "本回合累计 (ROUND)"
    )

    fun durationToLabel(duration: MatchState.StatDuration): String =
        durationLabelMap[duration] ?: duration.name

    // ── 统计维度 (StatDimensionKey) ──
    private val dimensionKeyLabelMap = mapOf(
        MatchState.StatDimensionKey.CARD to "单卡 (CARD)",
        MatchState.StatDimensionKey.GROUP to "卡牌分组 (GROUP)",
        MatchState.StatDimensionKey.PURPOSE to "意图标签 (PURPOSE)"
    )

    fun dimensionKeyToLabel(key: MatchState.StatDimensionKey): String =
        dimensionKeyLabelMap[key] ?: key.name
}
