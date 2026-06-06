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
) {
    companion object {
        /**
         * 默认规则表：与原有硬编码映射一致的默认规则。
         * 后续可扩展为从外部配置加载。
         */
        val DEFAULTS: List<PurposeTagIntentRule> = listOf(
            PurposeTagIntentRule(
                tagId = PurposeTagId.SAVE_LIFE,
                defaultStage = UseStage.DEFEND,
                defaultOrderWeight = 0.0,
                defaultReplanAfterUse = false,
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

        /** 按 priority 降序排列的规则索引 */
        val BY_PRIORITY: Map<PurposeTagId, PurposeTagIntentRule> =
            DEFAULTS.associateBy { it.tagId }
    }
}
