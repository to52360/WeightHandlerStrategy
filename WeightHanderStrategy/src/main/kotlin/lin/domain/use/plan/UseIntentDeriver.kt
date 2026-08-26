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
    ruleProvider: PurposeTagIntentRuleProvider = DefaultPurposeTagIntentRuleProvider()
) {
    private val ruleIndex: Map<PurposeTagId, PurposeTagIntentRule> =
        ruleProvider.rules().associateBy { it.tagId }

    /**
     * 根据配置推导运行时意图。
     *
     * stageOverride 优先级最高；没有显式指定时，按规则表推导默认阶段。
     * tagDefaultSurplusIdleThreshold 按多标签 defaultSurplusIdleThreshold 取 max（见 [resolveTagDefaultSurplusIdleThreshold]）。
     */
    fun derive(config: CardUseConfig): UseIntent {
        val tagDefaultN = resolveTagDefaultSurplusIdleThreshold(config)
        if (config.stageOverride != null) {
            return UseIntent(
                stage = config.stageOverride,
                replanAfterUse = config.replanAfterUse,
                orderWeight = config.orderWeight,
                tagDefaultSurplusIdleThreshold = tagDefaultN
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
                orderWeight = if (config.orderWeight == 0.0) bestRule.defaultOrderWeight else config.orderWeight,
                tagDefaultSurplusIdleThreshold = tagDefaultN
            )
        } else {
            UseIntent(
                stage = UseStage.GENERAL,
                replanAfterUse = config.replanAfterUse,
                orderWeight = config.orderWeight,
                tagDefaultSurplusIdleThreshold = tagDefaultN
            )
        }
    }

    /**
     * tag 默认余费门槛 N 推导（T-026，替代原 resolveCandidatePolicy）：多标签命中的
     * defaultSurplusIdleThreshold 取 **max**（有战术身份即惜售，保守方向——原 candidatePolicy
     * 冲突软回落 NORMAL 的语义现被 max 覆盖：任一标签声明惜售即整体惜售）。
     */
    private fun resolveTagDefaultSurplusIdleThreshold(config: CardUseConfig): Int? =
        config.purposeTags
            .mapNotNull { ruleIndex[it]?.defaultSurplusIdleThreshold }
            .maxOrNull()
}


