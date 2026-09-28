package lin.mcp.coverage

/**
 * 卡级覆盖分类（**判据单点**）。
 *
 * ## 为什么要有它
 * 卡池三张清单（`cardTrees` / `tagOnlyCards` / `uncoveredCards`）原先各自重写一遍
 * 「在不在组内 / 有无用途标签 / 有无 CARD 单卡树」的组合谓词 —— 同一判据散在三处，
 * 改一处必须记得改另外两处，漏改即**清单互相矛盾**（同张卡既不算裸卡也不算有标签）。
 * 现在三张清单都从 [classifyCardCoverage] 派生，判据只有一个定义。
 *
 * ## 分类语义（互斥且穷尽）
 * | 类别 | 条件 | 含义 / 去向 |
 * |---|---|---|
 * | [CARD_TREE] | 有 CARD 单卡树 | 已有单卡微观规则 ⇒ 进 `cardTrees`（`inGroup` 字段区分是否另外进组） |
 * | [IN_GROUP] | 无 CARD 树、在组内 | 有分组级策略 ⇒ 不进任何缺口清单 |
 * | [TAG_ONLY] | 无 CARD 树、不在组、有标签 | 只有全局兜底、无卡组特异性策略 ⇒ 进 `tagOnlyCards`（覆盖质量缺口） |
 * | [BARE] | 其余（无树、不在组、无标签） | 完全无机制 ⇒ 进 `uncoveredCards` |
 *
 * 顺序即优先级：**有 CARD 树优先于在组**（有单卡树的卡无论是否进组都归 [CARD_TREE]）。
 */
enum class CardCoverageClass { CARD_TREE, IN_GROUP, TAG_ONLY, BARE }

/** 单点分类：[inGroup] 是否属于任一分组、[hasTag] 是否有用途标签、[hasCardTree] 是否被 CARD 单卡树覆盖。 */
fun classifyCardCoverage(inGroup: Boolean, hasTag: Boolean, hasCardTree: Boolean): CardCoverageClass = when {
    hasCardTree -> CardCoverageClass.CARD_TREE
    inGroup -> CardCoverageClass.IN_GROUP
    hasTag -> CardCoverageClass.TAG_ONLY
    else -> CardCoverageClass.BARE
}
