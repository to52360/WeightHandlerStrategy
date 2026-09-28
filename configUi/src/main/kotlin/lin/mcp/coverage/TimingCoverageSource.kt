package lin.mcp.coverage

import lin.bean.usePlan.UseStage
import lin.mcp.ConfigSnapshot

/**
 * **时序**覆盖来源（`T-FO-020` S4，用户举例的"时序"语义）。
 *
 * ## 为什么它不与 `orchestration` 重复
 * - `orchestration` = **分组级**编排：分组自己声明的 `stageOverride` / `conditionalStage`（写在这个组上）；
 * - `tagTiming` = **标签级**编排：组内卡片因**用途标签**继承到的默认阶段与排序权重
 *   （来自 `purpose_tag_rule`，接口化在 [ConfigSnapshot.tagIntentByTag]）。
 *
 * 两者叠加正是引擎的真实行为（`UseIntentDeriver` 按标签取默认 stage，分组 override 再覆盖它），
 * 现在这两层在"有没有编排机制"这件事上**分开可见**——只打标签、没写分组 override 的组，
 * 以前是 UNCOVERED（看起来"没策略"），现在按编排来源计入 `ORCHESTRATED`（语义更准：它确实被排过序）。
 *
 * ## 过滤：只算"有效编排"
 * `GENERAL` + 权重 0 的标签规则等于"不表达任何编排"（如 `VALUE`：阶段回落 GENERAL、无排序权重）
 * ⇒ 不算覆盖，否则「凡是打过标签的组」都会变成 ORCHESTRATED，把噪音当覆盖。
 */
fun tagTimingEntries(bindingId: String, snap: ConfigSnapshot): List<Map<String, Any?>> {
    val binding = snap.bindingsById[bindingId] ?: return emptyList()
    val tags = binding.cardIds.flatMap { snap.tagsByCard[it].orEmpty() }.distinct().sorted()
    return tags.mapNotNull { tag ->
        val intent = snap.tagIntentByTag[tag] ?: return@mapNotNull null
        if (intent.stage == UseStage.GENERAL && intent.orderWeight == 0.0) return@mapNotNull null
        mapOf(
            "tag" to tag,
            "defaultStage" to intent.stage.name,
            "defaultOrderWeight" to intent.orderWeight
        )
    }
}
