package lin.repository.card_group

import lin.bean.usePlan.PurposeTagId
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
 * **统一语义（D-TG-018）**：树与时序**同向** —— 「**声明 = 生效，未声明 = 不生效**」。
 * 卡组未引用预设 ⇒ 一条声明都没有 ⇒ 用途树不输出、时序无规则（**唯一例外**：归属本卡组的用途树
 * 属「建树即声明」，见 [narrowOwnTreeTags]）。故不再有「不引用预设 = 兜底全开」的隐式作用。
 *
 * 语义见 `cross-dialogue/Q-TG-003-use-preset-final.md` §3.3 / §3.4；
 * **卡组专属树（归属即拥有、不走白名单）**见 `cross-dialogue/Q-TG-004-deck-private-purpose-tree.md`（形态 D）；
 * **声明模型**见 `cross-dialogue/Q-TG-011-timing-declaration-model.md`（收口为 D-TG-018）。
 */
class DimensionItemResolver {

    /**
     * 树选择的合并输入。
     *
     * @param presetKeepByTag 用途 → 本维度**已声明**的树 id 集合（预设白名单；未引用预设时为**空表**）。
     *   空缺的用途 = 未声明 ⇒ 该用途的用途树不输出（空预设 = 全禁）。
     * @param consumerExcludeByTag 用途 → 卡组**额外排除**的树 id 集合。
     */
    data class TreeSelection(
        val presetKeepByTag: Map<String, Set<String>>,
        val consumerExcludeByTag: Map<String, Set<String>>
    ) {
        companion object {
            /** 无任何声明且无增量项 —— 用途树全不输出（D-TG-018：不引用预设 = 合法终态，无隐式作用）。 */
            val NONE = TreeSelection(presetKeepByTag = emptyMap(), consumerExcludeByTag = emptyMap())
        }
    }

    /**
     * 由已解码的「用途 → 树 id 集合」构造树选择。
     *
     * [presetReferenced] = false（卡组未引用预设）⇒ `presetKeepByTag` 取空表
     * ⇒ 全局共享用途树**全部不输出**；卡组私有用途树不受影响（归属即拥有）。
     */
    fun treeSelection(
        presetReferenced: Boolean,
        presetKeepByTag: Map<String, Set<String>>,
        consumerExcludeByTag: Map<String, Set<String>>
    ): TreeSelection = TreeSelection(
        presetKeepByTag = if (presetReferenced) presetKeepByTag else emptyMap(),
        consumerExcludeByTag = consumerExcludeByTag
    )

    /**
     * 某棵**全局共享树**的收窄后的用途 tag 列表（即新的 `bindingIds`）。
     *
     * 逐 tag 判定：有预设白名单而该 tag 未声明 ⇒ 禁；白名单不含本树 ⇒ 禁；消费方已排除 ⇒ 禁。
     * **返回空列表 ⇒ 整棵树不输出**（调用方负责丢弃）。
     */
    fun narrowTreeTags(treeId: String, treeTags: List<String>, selection: TreeSelection): List<String> =
        treeTags.filter { tag ->
            inPresetWhitelist(treeId, tag, selection) && !consumerExcluded(treeId, tag, selection)
        }

    /**
     * 某棵**卡组专属树**（`manager_id` = 当前卡组）的收窄。
     *
     * 归属即拥有 ⇒ **不受预设白名单约束**（预设管的是公共资源怎么用，私有资源天然属于本卡组），
     * 但**仍受消费方增量项约束** —— 保留"本卡组再减"这个微调入口（需要时仍可把它排除）。
     */
    fun narrowOwnTreeTags(treeId: String, treeTags: List<String>, selection: TreeSelection): List<String> =
        treeTags.filter { tag -> !consumerExcluded(treeId, tag, selection) }

    /** 预设白名单判定：该用途必须**显式声明**本树 —— 未引用预设（空表）⇒ 该用途未声明 ⇒ 不放行。 */
    private fun inPresetWhitelist(treeId: String, tag: String, selection: TreeSelection): Boolean {
        val whitelist = selection.presetKeepByTag[tag] ?: return false // 该用途未声明 ⇒ 全禁
        return treeId in whitelist
    }

    /** 消费方增量项判定：本卡组额外排除的树，两类树（全局 / 专属）都受它约束。 */
    private fun consumerExcluded(treeId: String, tag: String, selection: TreeSelection): Boolean =
        treeId in (selection.consumerExcludeByTag[tag] ?: emptySet())

    /**
     * **被禁用的用途清单** = 「库中已有用途树」的 tag − 预设已声明的 tag。
     *
     * 由 `save_strategy_preset` / `get` 回报 —— 防"以为在用、其实被禁"（信息，不是开关）。
     */
    fun disabledPurposes(treeTagUniverse: Set<String>, presetDeclaredTags: Set<String>): List<String> =
        (treeTagUniverse - presetDeclaredTags).sorted()

    // ─────────────────────── 时序 ───────────────────────

    /** 逐字段合并两层声明：[upper]（消费方）优先于 [lower]（预设），两层都缺席 ⇒ [missing]。 */
    fun mergeTiming(
        lower: TimingOverride?,
        upper: TimingOverride?,
        missing: TimingOverride = TimingOverride()
    ): TimingOverride {
        if (lower == null) return upper ?: missing
        if (upper == null) return lower
        return TimingOverride(
            defaultStage = upper.defaultStage ?: lower.defaultStage,
            defaultOrderWeight = upper.defaultOrderWeight ?: lower.defaultOrderWeight,
            defaultReplanAfterUse = upper.defaultReplanAfterUse ?: lower.defaultReplanAfterUse,
            surplusIdleThreshold = upper.surplusIdleThreshold ?: lower.surplusIdleThreshold,
            // ⚠️ 必须逐字段合并（漏掉本行 ⇒ 预设声明的 priority 一旦被增量项触碰即静默丢失，回落全局行）
            priority = upper.priority ?: lower.priority
        )
    }

    /**
     * 由**声明**生成一条完整规则（D-TG-018：规则由声明产生，不再"改全局规则行的字段"）。
     *
     * 逐字段取值链：**本声明 > [fallback]（全局行，仅作缺省值源） > `PurposeTagIntentRule` 内置默认**
     * （`stage = GENERAL` / `priority = 100` 等）。
     * ⚠️ 内置默认只在「**已被声明**、但某字段没填」时落位 ⇒ 未声明的用途**根本不进本函数**，
     * 因此它不构成「未声明 = 吃全局」的隐式兜底。
     *
     * @param fallback 全局规则行（`purpose_tag_rule`）；null = 无该行
     */
    fun toRule(
        tagId: String,
        declared: TimingOverride,
        fallback: PurposeTagIntentRule? = null
    ): PurposeTagIntentRule {
        val stage = declared.defaultStage?.let { name ->
            runCatching { UseStage.valueOf(name) }.getOrElse {
                myLog.warn { "时序声明的 stage 非法，已回落到缺省值: tag=$tagId stage=$name" }
                null
            }
        }
        if (declared.priority == null && fallback?.priority == null) {
            myLog.debug { "用途 $tagId 的声明与全局规则均未给 priority，采用内置默认 100（合法缺省）" }
        }
        val defaults = DEFAULT_RULE
        return PurposeTagIntentRule(
            tagId = PurposeTagId(tagId),
            defaultStage = stage ?: fallback?.defaultStage ?: defaults.defaultStage,
            defaultOrderWeight = declared.defaultOrderWeight
                ?: fallback?.defaultOrderWeight
                ?: defaults.defaultOrderWeight,
            priority = declared.priority ?: fallback?.priority ?: defaults.priority,
            defaultReplanAfterUse = declared.defaultReplanAfterUse
                ?: fallback?.defaultReplanAfterUse
                ?: defaults.defaultReplanAfterUse,
            defaultSurplusIdleThreshold = if (declared.surplusIdleThreshold != null) {
                declared.surplusIdleThreshold.value   // 键存在 ⇒ 已声明（显式 null = 「不设门槛」）
            } else {
                fallback?.defaultSurplusIdleThreshold
            }
        )
    }

    private companion object {
        /** 缺省值载体（与 [PurposeTagIntentRule] 内的默认值同源，避免第三处硬编码口径）。 */
        val DEFAULT_RULE = PurposeTagIntentRule(tagId = PurposeTagId(""), defaultStage = UseStage.GENERAL)
    }
}
