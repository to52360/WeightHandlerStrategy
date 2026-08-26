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
 * @param defaultSurplusIdleThreshold 默认余费门槛 N（T-026，替代原 candidatePolicy 预设）；null = 未声明，回落 0。
 *   语义 = 声明「此牌未来有更大收益，平时惜售」：战术命中（ts>0）兑现即放行，未命中时需空闲 ≥ 牌费+N 才垫。
 *   多标签命中取 max（有战术身份即惜售，保守方向），见 [lin.domain.use.plan.UseIntentDeriver]。
 */
data class PurposeTagIntentRule(
    val tagId: PurposeTagId,
    val defaultStage: UseStage,
    val defaultOrderWeight: Double = 0.0,
    val defaultReplanAfterUse: Boolean = false,
    val priority: Int = 100,
    val defaultSurplusIdleThreshold: Int? = null
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
 * defaultSurplusIdleThreshold 映射（T-026，替代原 candidatePolicy 预设）：战术功能标签（保命/解场/斩杀/成长/过牌）
 * → N=1（有未来收益声明，平时惜售），普通价值与硬币 → null（N=0 全自由）。
 * 预设值先设后实战校准（Q-026 拍板②）；多标签命中取 max（UseIntentDeriver），无冲突异常。
 */
class DefaultPurposeTagIntentRuleProvider : PurposeTagIntentRuleProvider {
    override fun rules(): List<PurposeTagIntentRule> = listOf(
        PurposeTagIntentRule(
            tagId = PurposeTagId.SAVE_LIFE,
            defaultStage = UseStage.DEFEND,
            priority = 400,
            defaultSurplusIdleThreshold = 1
        ),
        PurposeTagIntentRule(
            tagId = PurposeTagId.CLEAN,
            defaultStage = UseStage.CLEAR,
            priority = 300,
            defaultSurplusIdleThreshold = 1
        ),
        PurposeTagIntentRule(
            tagId = PurposeTagId.FINISH,
            defaultStage = UseStage.END,
            priority = 200,
            defaultSurplusIdleThreshold = 1
        ),
        PurposeTagIntentRule(
            tagId = PurposeTagId.GREED,
            defaultStage = UseStage.SETUP,
            priority = 100,
            defaultSurplusIdleThreshold = 1
        ),
        PurposeTagIntentRule(
            tagId = PurposeTagId.VALUE,
            defaultStage = UseStage.GENERAL,
            priority = 50,
            defaultSurplusIdleThreshold = null
        ),
        PurposeTagIntentRule(
            tagId = PurposeTagId.EXTRA_COST,
            defaultStage = UseStage.GENERAL,
            priority = 50,
            defaultSurplusIdleThreshold = null
        ),
        PurposeTagIntentRule(
            tagId = PurposeTagId.DRAW_CARD,
            defaultStage = UseStage.GENERAL,
            priority = 60,
            defaultSurplusIdleThreshold = 1
        )
    )
}
