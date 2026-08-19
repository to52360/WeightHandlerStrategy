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
     * candidatePolicy 按显式覆盖 > 唯一标签默认 > NORMAL 推导（见 [resolveCandidatePolicy]）。
     */
    fun derive(config: CardUseConfig): UseIntent {
        val candidatePolicy = resolveCandidatePolicy(config)
        if (config.stageOverride != null) {
            return UseIntent(
                stage = config.stageOverride,
                replanAfterUse = config.replanAfterUse,
                orderWeight = config.orderWeight,
                candidatePolicy = candidatePolicy
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
                candidatePolicy = candidatePolicy
            )
        } else {
            UseIntent(
                stage = UseStage.GENERAL,
                replanAfterUse = config.replanAfterUse,
                orderWeight = config.orderWeight,
                candidatePolicy = candidatePolicy
            )
        }
    }

    /**
     * 候选策略解析：显式覆盖 > 唯一标签默认 > NORMAL。
     *
     * 多个用途标签声明了互相冲突的 `defaultCandidatePolicy` 时属配置冲突——
     * 不按 priority 裁决，抛异常要求单卡/分组显式覆盖（fail-fast，启动期暴露）。
     */
    private fun resolveCandidatePolicy(config: CardUseConfig): CandidatePolicy {
        config.candidatePolicy?.let { return it }
        val declared = config.purposeTags
            .mapNotNull { ruleIndex[it]?.defaultCandidatePolicy }
            .distinct()
        return when (declared.size) {
            0 -> CandidatePolicy.NORMAL
            1 -> declared.first()
            else -> throw IllegalStateException(
                "候选策略冲突：用途标签 ${config.purposeTags} 声明了多个不同的 defaultCandidatePolicy（$declared），" +
                        "请在单卡或分组显式覆盖 candidatePolicy"
            )
        }
    }
}


