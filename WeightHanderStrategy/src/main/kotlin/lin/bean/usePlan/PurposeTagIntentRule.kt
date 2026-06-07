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
 */
data class PurposeTagIntentRule(
    val tagId: PurposeTagId,
    val defaultStage: UseStage,
    val defaultOrderWeight: Double = 0.0,
    val defaultReplanAfterUse: Boolean = false,
    val priority: Int = 100
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
 * 默认规则提供者：硬编码的 6 条基础规则。
 *
 * 作为 [PurposeTagIntentRuleProvider] 的 fallback 实现；
 * 后续可替换为数据库驱动或其他外部配置加载实现。
 */
class DefaultPurposeTagIntentRuleProvider : PurposeTagIntentRuleProvider {
    override fun rules(): List<PurposeTagIntentRule> = listOf(
        PurposeTagIntentRule(
            tagId = PurposeTagId.SAVE_LIFE,
            defaultStage = UseStage.DEFEND,
            priority = 400
        ),
        PurposeTagIntentRule(
            tagId = PurposeTagId.CLEAN,
            defaultStage = UseStage.CLEAR,
            priority = 300
        ),
        PurposeTagIntentRule(
            tagId = PurposeTagId.FINISH,
            defaultStage = UseStage.END,
            priority = 200
        ),
        PurposeTagIntentRule(
            tagId = PurposeTagId.GREED,
            defaultStage = UseStage.SETUP,
            priority = 100
        ),
        PurposeTagIntentRule(
            tagId = PurposeTagId.VALUE,
            defaultStage = UseStage.GENERAL,
            priority = 50
        ),
        PurposeTagIntentRule(
            tagId = PurposeTagId.EXTRA_COST,
            defaultStage = UseStage.GENERAL,
            priority = 50
        )
    )
}
