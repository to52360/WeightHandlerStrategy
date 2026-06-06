package lin.domain.use.plan

import lin.bean.usePlan.*

/**
 * 意图推导核心器。
 *
 * 基于 [PurposeTagIntentRule] 规则表推导默认出牌阶段与排序偏好。
 * 规则按 priority 降序匹配用途标签，取最高优先级命中规则。
 * stageOverride 存在时跳过规则推导。
 *
 * 默认出牌阶段由此统一推导；特例直接使用 stageOverride 指定。
 */
class UseIntentDeriver(
    private val rules: List<PurposeTagIntentRule> = PurposeTagIntentRule.DEFAULTS
) {
    private val ruleIndex: Map<PurposeTagId, PurposeTagIntentRule> =
        rules.associateBy { it.tagId }

    /**
     * 根据配置推导运行时意图。
     *
     * stageOverride 优先级最高；没有显式指定时，按规则表推导默认阶段。
     */
    fun derive(config: CardUseConfig): UseIntent {
        if (config.stageOverride != null) {
            return UseIntent(
                stage = config.stageOverride,
                replanAfterUse = config.replanAfterUse,
                orderWeight = config.orderWeight
            )
        }

        // 按 priority 降序在配置标签中查找最优匹配规则
        val bestRule = config.purposeTags
            .mapNotNull { ruleIndex[it] }
            .maxByOrNull { it.priority }

        return if (bestRule != null) {
            UseIntent(
                stage = bestRule.defaultStage,
                replanAfterUse = config.replanAfterUse,
                orderWeight = if (config.orderWeight == 0.0) bestRule.defaultOrderWeight else config.orderWeight
            )
        } else {
            UseIntent(
                stage = UseStage.GENERAL,
                replanAfterUse = config.replanAfterUse,
                orderWeight = config.orderWeight
            )
        }
    }
}

/**
 * 卡牌用途标签提供者（per cardId）。
 *
 * SPI 入口，configUi 通过此接口读取 card_purpose 表。
 * 引擎端提供默认实现返回空 CardPurpose。
 */
fun interface CardPurposeProvider {
    fun purposeOf(cardIds: Set<String>): Map<String, CardPurpose>
}

/**
 * 分组使用覆盖提供者（per groupId）。
 *
 * SPI 入口，configUi 通过此接口读取 group_use_override 表。
 * 引擎端提供默认实现返回空 Map。
 */
fun interface GroupUseOverrideProvider {
    fun overridesOf(groupIds: Set<String>): Map<String, GroupUseOverride>
}
