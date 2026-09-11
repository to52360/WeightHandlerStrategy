package lin.repository.card_group

import lin.bean.usePlan.PurposeTagIntentRule
import lin.bean.usePlan.UseStage
import lin.myLog

/**
 * 维度项的**纯合并规则**（无 IO ⇒ 可直接单测，不必构造 `EvaluatorTreeConfig`）。
 *
 * 两个 provider 共用：
 * - `SqliteTreeConfigProvider`：算「某棵用途树保留哪些 tag」→ **按 tag 收窄 `bindingIds`**；
 * - `SqlitePurposeTagIntentRuleProvider`：算「某用途最终生效的时序规则」。
 *
 * 语义见 `cross-dialogue/Q-TG-003-use-preset-final.md` §3.3 / §3.4。
 */
class DimensionItemResolver {

    /**
     * 树选择的合并输入。
     *
     * @param presetKeepByTag 用途 → 预设声明**保留**的树 id 集合。
     *   `null` = 卡组**未引用预设** ⇒ 不做白名单裁剪（全部生效，现状不变）；
     *   非 `null` 时**缺席的用途 = 未声明 ⇒ 该用途的用途树全禁**（空预设 = 全禁）。
     * @param consumerExcludeByTag 用途 → 卡组**额外排除**的树 id 集合。
     */
    data class TreeSelection(
        val presetKeepByTag: Map<String, Set<String>>?,
        val consumerExcludeByTag: Map<String, Set<String>>
    ) {
        companion object {
            /** 未引用预设且无增量项 —— 与改造前行为完全一致。 */
            val NONE = TreeSelection(presetKeepByTag = null, consumerExcludeByTag = emptyMap())
        }
    }

    /**
     * 由已解码的「用途 → 树 id 集合」构造树选择。
     *
     * [presetReferenced] = false（卡组未引用预设）⇒ 白名单为 `null`（不裁剪，全部生效）。
     */
    fun treeSelection(
        presetReferenced: Boolean,
        presetKeepByTag: Map<String, Set<String>>,
        consumerExcludeByTag: Map<String, Set<String>>
    ): TreeSelection = TreeSelection(
        presetKeepByTag = if (presetReferenced) presetKeepByTag else null,
        consumerExcludeByTag = consumerExcludeByTag
    )

    /**
     * 某棵树**收窄后的用途 tag 列表**（即新的 `bindingIds`）。
     *
     * 逐 tag 判定：有预设白名单而该 tag 未声明 ⇒ 禁；白名单不含本树 ⇒ 禁；消费方已排除 ⇒ 禁。
     * **返回空列表 ⇒ 整棵树不输出**（调用方负责丢弃）。
     */
    fun narrowTreeTags(treeId: String, treeTags: List<String>, selection: TreeSelection): List<String> =
        treeTags.filter { tag -> keepTag(treeId, tag, selection) }

    private fun keepTag(treeId: String, tag: String, selection: TreeSelection): Boolean {
        selection.presetKeepByTag?.let { presetKeep ->
            val whitelist = presetKeep[tag] ?: return false // 该用途未声明 ⇒ 全禁
            if (treeId !in whitelist) return false
        }
        return treeId !in (selection.consumerExcludeByTag[tag] ?: emptySet())
    }

    /**
     * **被禁用的用途清单** = 「库中已有用途树」的 tag − 预设已声明的 tag。
     *
     * 由 `save_strategy_preset` / `get` 回报 —— 防"以为在用、其实被禁"（信息，不是开关）。
     */
    fun disabledPurposes(treeTagUniverse: Set<String>, presetDeclaredTags: Set<String>): List<String> =
        (treeTagUniverse - presetDeclaredTags).sorted()

    // ─────────────────────── 时序 ───────────────────────

    /** 逐字段合并两层覆盖：[upper]（消费方）优先于 [lower]（预设）。 */
    fun mergeTiming(lower: TimingOverride?, upper: TimingOverride?): TimingOverride {
        if (lower == null) return upper ?: TimingOverride()
        if (upper == null) return lower
        return TimingOverride(
            defaultStage = upper.defaultStage ?: lower.defaultStage,
            defaultOrderWeight = upper.defaultOrderWeight ?: lower.defaultOrderWeight,
            defaultReplanAfterUse = upper.defaultReplanAfterUse ?: lower.defaultReplanAfterUse,
            surplusIdleThreshold = upper.surplusIdleThreshold ?: lower.surplusIdleThreshold
        )
    }

    /**
     * 把时序覆盖施加到全局规则上。
     *
     * 未声明字段回落全局；`ThresholdPatch(null)` ⇒ **覆盖为「不设门槛」**（K-TG-005 的落点）。
     */
    fun applyTiming(rule: PurposeTagIntentRule, override: TimingOverride): PurposeTagIntentRule {
        val stage = override.defaultStage?.let { name ->
            runCatching { UseStage.valueOf(name) }.getOrElse {
                myLog.warn { "时序覆盖的 stage 非法，已忽略该字段: tag=${rule.tagId.value} stage=$name" }
                null
            }
        }
        return rule.copy(
            defaultStage = stage ?: rule.defaultStage,
            defaultOrderWeight = override.defaultOrderWeight ?: rule.defaultOrderWeight,
            defaultReplanAfterUse = override.defaultReplanAfterUse ?: rule.defaultReplanAfterUse,
            defaultSurplusIdleThreshold = if (override.surplusIdleThreshold != null) {
                override.surplusIdleThreshold.value
            } else {
                rule.defaultSurplusIdleThreshold
            }
        )
    }
}
