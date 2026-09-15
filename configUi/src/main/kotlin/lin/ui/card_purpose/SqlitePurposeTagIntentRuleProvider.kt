package lin.ui.card_purpose

import lin.bean.usePlan.PurposeTagId
import lin.bean.usePlan.PurposeTagIntentRule
import lin.bean.usePlan.UseStage
import lin.myLog
import lin.repository.card_group.CurrentDeckContext
import lin.repository.card_group.DimensionItemResolver
import lin.repository.card_group.DimensionScope
import lin.repository.card_group.StrategyPresetRepository
import lin.repository.card_purpose.PurposeTagRuleEntity
import lin.repository.card_purpose.PurposeTagRuleRepository
import lin.serviceLoader.provider.PurposeTagIntentRuleProvider

/**
 * 数据库驱动的用途意图规则提供者（T-TG-007/008/015；T-TG-028 改声明模型）。
 *
 * **规则由声明产生（D-TG-018）**：卡组引用的**预设**（`scope=PRESET`）或**本卡组增量项**
 * （`scope=CARD_GROUP`）**声明**了某用途 ⇒ 为该卡组生成一条**完整规则**（stage / orderWeight / N / replan
 * + 可选 priority）；**未声明 = 无规则 = 不参与 `UseIntentDeriver` 的 priority 选优**。
 * 三动作（排除 / 增加 / 覆盖）因此坍缩为「**声明 or 不声明**」，与树白名单同构。
 *
 * **卡组未引用预设 = 合法终态**（精准规则场景，无隐式作用）⇒ 返回**空规则集**
 * （用途树侧同步：`SqliteTreeConfigProvider` 不输出全局共享用途树）。
 *
 * 全局 `purpose_tag_rule` **不再是运行时兜底**，只作两用：
 * ① 新建预设时的默认值预填（编辑期，见 `PresetCatalogLoader`）；② **priority 与缺省字段的来源**
 * （声明里没写 ⇒ 取全局行 ⇒ 都没有则用 `PurposeTagIntentRule` 默认，debug 记录，不 warn 刷屏）。
 *
 * 合并规则本身在 [DimensionItemResolver]（纯函数，可单测），本类只做 IO。
 * 通过 SPI 通道注入引擎（`CardConfigBindingTask` 从 Koin 解析）。
 *
 * ⚠️ **不再有「表为空 ⇒ 回落整套硬编码」的降级**（T-TG-008 双层保险里 configUi 侧那层已移除）——
 * 它正是本次要消灭的隐式兜底；引擎侧 Koin 取不到 provider 时的硬编码回落**保留**。
 */
class SqlitePurposeTagIntentRuleProvider(
    private val repository: PurposeTagRuleRepository,
    private val presetRepository: StrategyPresetRepository,
    private val currentDeck: CurrentDeckContext,
    private val resolver: DimensionItemResolver
) : PurposeTagIntentRuleProvider {

    override fun rules(): List<PurposeTagIntentRule> {
        // 全局行退化为「缺省值来源」（字段没填时落位 + priority 缺省），**不再逐条产出规则**。
        val globalByTag = repository.findAll().mapNotNull { row -> toRuleOrNull(row) }.associateBy { it.tagId.value }

        val deck = currentDeck.current()
        val presetId = deck?.presetId?.takeIf { it.isNotBlank() }
        val presetTimings = presetId
            ?.let { presetRepository.findTimings(DimensionScope.PRESET, it) }
            ?: emptyMap()
        val consumerTimings = deck
            ?.let { presetRepository.findTimings(DimensionScope.CARD_GROUP, it.id) }
            ?: emptyMap()

        // 两层声明取并集：缺席的用途 = 未声明 ⇒ 无规则（D-TG-018）。
        // ⚠️ 增量项不能"只减"或凭空造规则 —— 它只能覆盖已声明用途的字段，或自行声明一个新用途。
        return (presetTimings.keys + consumerTimings.keys).map { tag ->
            val declared = resolver.mergeTiming(
                lower = presetTimings[tag],
                upper = consumerTimings[tag]
            )
            resolver.toRule(tagId = tag, declared = declared, fallback = globalByTag[tag])
        }
    }

    /** 全局行 `default_stage` 非法 ⇒ 视为「无该行」（只影响缺省值来源，不影响任何规则的存在性）。 */
    private fun toRuleOrNull(row: PurposeTagRuleEntity): PurposeTagIntentRule? {
        val stage = runCatching { UseStage.valueOf(row.defaultStage) }.getOrNull()
        if (stage == null) {
            myLog.warn { "用途规则 stage 非法，已忽略该全局行（仅作缺省值源）: tag=${row.tagId} stage=${row.defaultStage}" }
            return null
        }
        return PurposeTagIntentRule(
            tagId = PurposeTagId(row.tagId),
            defaultStage = stage,
            defaultOrderWeight = row.defaultOrderWeight,
            priority = row.priority,
            defaultReplanAfterUse = row.defaultReplanAfterUse,
            defaultSurplusIdleThreshold = row.defaultSurplusIdleThreshold
        )
    }
}
