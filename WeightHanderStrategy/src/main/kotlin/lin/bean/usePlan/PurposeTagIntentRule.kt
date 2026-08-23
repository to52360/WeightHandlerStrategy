package lin.bean.usePlan

/**
 * 用途标签默认意图规则。
 *
 * 描述每个 [PurposeTagId] 在不被 card/group override 覆盖时的默认出牌意图字段。
 * [UseIntentDeriver] 按 priority 降序匹配标签，决定默认 [UseStage] 等。
 *
 * @param tagId 用途标签
 * @param defaultStage 该标签默认映射的出牌阶段
 * @param defaultOrderWeight 默认排序权重
 * @param defaultReplanAfterUse 使用后是否需重新规划
 * @param priority 优先级（数值越大越优先匹配）
 * @param defaultCandidatePolicy 候选策略默认值；null = 未声明，回落 NORMAL。多标签声明冲突需显式覆盖。
 */
data class PurposeTagIntentRule(
    val tagId: PurposeTagId,
    val defaultStage: UseStage,
    val defaultOrderWeight: Double = 0.0,
    val defaultReplanAfterUse: Boolean = false,
    val priority: Int = 100,
    val defaultCandidatePolicy: CandidatePolicy? = null
)

/**
 * 用途标签意图规则的数据源接口。
 *
 * 解耦规则来源，方便后续切换为数据库、SPI、配置文件等实现。
 */
interface PurposeTagIntentRuleProvider {
    fun rules(): List<PurposeTagIntentRule>
}

/**
 * 默认规则提供者：硬编码的 7 条基础规则。
 *
 * 作为 [PurposeTagIntentRuleProvider] 的 fallback 实现；
 * 后续可替换为数据库驱动或其他外部配置加载实现。
 *
 * defaultCandidatePolicy 映射（review T-005 建议）：战术功能标签（保命/解场/斩杀/成长/过牌）
 * → TACTICS_DOMINANT，普通价值与硬币 → NORMAL。
 * 注意：预设后，一张卡若同时命中「不同 defaultCandidatePolicy 的标签」且未显式覆盖，
 * 启动期推导将抛冲突异常（fail-fast），需在单卡/分组显式覆盖 candidatePolicy。
 */
class DefaultPurposeTagIntentRuleProvider : PurposeTagIntentRuleProvider {
    override fun rules(): List<PurposeTagIntentRule> = listOf(
        PurposeTagIntentRule(
            tagId = PurposeTagId.SAVE_LIFE,
            defaultStage = UseStage.DEFEND,
            priority = 400,
            defaultCandidatePolicy = CandidatePolicy.TACTICS_DOMINANT
        ),
        PurposeTagIntentRule(
            tagId = PurposeTagId.CLEAN,
            defaultStage = UseStage.CLEAR,
            priority = 300,
            defaultCandidatePolicy = CandidatePolicy.TACTICS_DOMINANT
        ),
        PurposeTagIntentRule(
            tagId = PurposeTagId.FINISH,
            defaultStage = UseStage.END,
            priority = 200,
            defaultCandidatePolicy = CandidatePolicy.TACTICS_DOMINANT
        ),
        PurposeTagIntentRule(
            tagId = PurposeTagId.GREED,
            defaultStage = UseStage.SETUP,
            priority = 100,
            defaultCandidatePolicy = CandidatePolicy.TACTICS_DOMINANT
        ),
        PurposeTagIntentRule(
            tagId = PurposeTagId.VALUE,
            defaultStage = UseStage.GENERAL,
            priority = 50,
            defaultCandidatePolicy = CandidatePolicy.NORMAL
        ),
        PurposeTagIntentRule(
            tagId = PurposeTagId.EXTRA_COST,
            defaultStage = UseStage.GENERAL,
            priority = 50,
            defaultCandidatePolicy = CandidatePolicy.NORMAL
        ),
        PurposeTagIntentRule(
            tagId = PurposeTagId.DRAW_CARD,
            defaultStage = UseStage.GENERAL,
            priority = 60,
            defaultCandidatePolicy = CandidatePolicy.TACTICS_DOMINANT
        )
    )
}
