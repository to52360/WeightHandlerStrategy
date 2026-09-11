package lin.ui.card_purpose

import lin.bean.usePlan.DefaultPurposeTagIntentRuleProvider
import lin.bean.usePlan.PurposeTagId
import lin.bean.usePlan.PurposeTagIntentRule
import lin.bean.usePlan.UseStage
import lin.myLog
import lin.repository.card_group.CurrentDeckContext
import lin.repository.card_group.DimensionItemResolver
import lin.repository.card_group.DimensionScope
import lin.repository.card_group.StrategyPresetRepository
import lin.repository.card_purpose.PurposeTagRuleRepository
import lin.serviceLoader.provider.PurposeTagIntentRuleProvider

/**
 * 数据库驱动的用途意图规则提供者（T-TG-007/008/015）。
 *
 * 规则来源三层，逐字段按「消费方 > 预设 > 全局」合并：
 * 1. **全局默认**：`purpose_tag_rule`（替代硬编码 [DefaultPurposeTagIntentRuleProvider]）；
 * 2. **预设覆盖**（`scope=PRESET`）：当前卡组引用的预设对该用途的时序声明；
 * 3. **消费方增量项**（`scope=CARD_GROUP`）：本卡组在预设之上的偏差（压过预设）。
 *
 * 合并规则本身在 [DimensionItemResolver]（纯函数，可单测），本类只做 IO。
 * 通过 SPI 通道注入引擎（`CardConfigBindingTask` 从 Koin 解析）。
 *
 * **降级**（T-TG-008）：表为空视为异常，回落内置硬编码并告警 ——
 * 否则所有标签会同时失去 stage / N / replan 默认值，且表面无异常（静默退化）。
 */
class SqlitePurposeTagIntentRuleProvider(
    private val repository: PurposeTagRuleRepository,
    private val presetRepository: StrategyPresetRepository,
    private val currentDeck: CurrentDeckContext,
    private val resolver: DimensionItemResolver
) : PurposeTagIntentRuleProvider {

    override fun rules(): List<PurposeTagIntentRule> {
        val rows = repository.findAll()
        if (rows.isEmpty()) {
            myLog.warn {
                "purpose_tag_rule 表为空，回落内置硬编码规则" +
                        "（否则所有用途标签将失去 stage / N / replan 默认值）"
            }
            return DefaultPurposeTagIntentRuleProvider().rules()
        }

        val base = rows.mapNotNull { row ->
            val stage = runCatching { UseStage.valueOf(row.defaultStage) }.getOrNull()
            if (stage == null) {
                myLog.warn { "用途规则 stage 非法，已跳过: tag=${row.tagId} stage=${row.defaultStage}" }
                return@mapNotNull null
            }
            PurposeTagIntentRule(
                tagId = PurposeTagId(row.tagId),
                defaultStage = stage,
                defaultOrderWeight = row.defaultOrderWeight,
                priority = row.priority,
                defaultReplanAfterUse = row.defaultReplanAfterUse,
                defaultSurplusIdleThreshold = row.defaultSurplusIdleThreshold
            )
        }

        val deck = currentDeck.current() ?: return base
        val presetId = deck.presetId?.takeIf { it.isNotBlank() }
        val presetTimings = presetId
            ?.let { presetRepository.findTimings(DimensionScope.PRESET, it) }
            ?: emptyMap()
        val consumerTimings = presetRepository.findTimings(DimensionScope.CARD_GROUP, deck.id)
        if (presetTimings.isEmpty() && consumerTimings.isEmpty()) return base

        // ⚠️ **只作用于已有规则行的用途** —— 无规则行 = 不参与 priority 选优（D-TG-003），
        // 时序覆盖**不新增规则行**（新增会改变选优集合，且默认 priority 会与他人平手导致结果不定）。
        // 「预设声明了无规则行的用途」由 MCP 侧前置校验并报错，此处只跳过。
        return base.map { rule ->
            val tag = rule.tagId.value
            val merged = resolver.mergeTiming(
                lower = presetTimings[tag],
                upper = consumerTimings[tag]
            )
            if (merged.isEmpty) rule else resolver.applyTiming(rule, merged)
        }
    }
}
