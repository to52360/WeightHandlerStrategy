package lin.ui.card_group.behavior

import lin.bean.usePlan.CandidatePolicy
import lin.bean.usePlan.UseStage
import lin.domain.MatchState

/**
 * 行为编辑器 UI 中文化显示与底层 ID/Enum 双向映射工具类。
 */
object BehaviorDisplayMappers {

    // ── 出牌阶段 (UseStage) ──
    private val stageLabelMap = mapOf(
        UseStage.RESOURCE to "资源 (RESOURCE)",
        UseStage.SETUP to "铺场 (SETUP)",
        UseStage.CLEAR to "解场 (CLEAR)",
        UseStage.DEFEND to "防御 (DEFEND)",
        UseStage.COMBO to "斩杀 (COMBO)",
        UseStage.GENERAL to "常规 (GENERAL)",
        UseStage.END to "回合结束 (END)"
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

    // ── 候选策略 (CandidatePolicy，D-003) ──
    private const val POLICY_UNSET_LABEL = "(不覆盖)"

    private val policyLabelMap = mapOf(
        CandidatePolicy.NORMAL to "正常 (NORMAL)",
        CandidatePolicy.TACTICS_DOMINANT to "战术主导 (TACTICS_DOMINANT)",
        CandidatePolicy.SURPLUS_ONLY to "余费专用 (SURPLUS_ONLY)"
    )
    private val labelToPolicyMap = policyLabelMap.entries.associate { (policy, label) -> label to policy }

    fun policyToLabel(policy: CandidatePolicy?): String =
        policy?.let { policyLabelMap[it] ?: it.name } ?: POLICY_UNSET_LABEL

    fun labelToPolicy(label: String?): CandidatePolicy? {
        if (label == null || label == POLICY_UNSET_LABEL) return null
        return labelToPolicyMap[label] ?: runCatching { CandidatePolicy.valueOf(label) }.getOrNull()
    }

    fun allPolicyLabels(): List<String> = listOf(POLICY_UNSET_LABEL) + CandidatePolicy.entries.map { policyToLabel(it) }

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
