package lin.domain.use.plan

import lin.bean.usePlan.*
import lin.serviceLoader.provider.PurposeTagIntentRuleProvider

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
     * **逐字段独立解析**（T-039）：每个字段各自按「显式配置 > 标签默认 > 内建兜底」三级回落，
     * 互不阻断。原实现以 `stageOverride != null` 提前 return，导致显式指定阶段时
     * `defaultOrderWeight` 被一并跳过——阶段轴与排序权重轴本应正交（同 N / replan 的既有约定）。
     *
     * - `stage`：显式 stageOverride > 最优标签规则 defaultStage > GENERAL
     * - `orderWeight`：显式配置（null 表未配置）> 最优标签规则 defaultOrderWeight > 0.0
     * - `replanAfterUse`：显式声明 OR 标签兜底（见 [resolveTagDefaultReplanAfterUse]）
     * - `tagDefaultSurplusIdleThreshold`：多标签取 max（见 [resolveTagDefaultSurplusIdleThreshold]）
     */
    fun derive(config: CardUseConfig): UseIntent {
        // 按 priority 降序在配置标签中查找最优匹配规则
        val bestRule = config.purposeTags
            .mapNotNull { ruleIndex[it] }
            .maxByOrNull { it.priority }

        // T-036：OR 合并——标签全局兜底 / 分组 / 单卡任一声明即 replan。
        // 多评估一次安全，漏评估会让后续牌按过时战场信息决策（清场场景）。
        val tagReplan = resolveTagDefaultReplanAfterUse(config)
        return UseIntent(
            stage = config.stageOverride ?: bestRule?.defaultStage ?: UseStage.GENERAL,
            replanAfterUse = config.replanAfterUse || tagReplan,
            // T-039：orderWeight 可空后，"显式配 0 覆盖标签默认 1" 才成为可能
            orderWeight = config.orderWeight ?: bestRule?.defaultOrderWeight ?: 0.0,
            tagDefaultSurplusIdleThreshold = resolveTagDefaultSurplusIdleThreshold(config),
            tagDefaultReplanAfterUse = tagReplan
        )
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

    /**
     * tag 全局兜底「打出后重新评估」推导（T-036）：多标签命中取 **any**。
     *
     * 与 [resolveTagDefaultSurplusIdleThreshold] 的 max 同构——同为「保守方向」合并：
     * N 取 max（任一标签惜售即惜售），replan 取 any（任一标签要求重评估即重评估）。
     *
     * 注意：本推导**不因 stageOverride 而跳过**——replan 属于执行生命周期轴，
     * 与阶段轴正交（同 [resolveTagDefaultSurplusIdleThreshold]）。
     */
    private fun resolveTagDefaultReplanAfterUse(config: CardUseConfig): Boolean =
        config.purposeTags.any { ruleIndex[it]?.defaultReplanAfterUse == true }
}


