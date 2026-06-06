package lin.bean.usePlan

/**
 * 战略用途标签，用于规则和评估树进行宏观决策评估。
 *
 * 这里的标签只描述"为什么这张牌值得被选中"，不描述真实出牌顺序。
 * 出牌默认顺序由 UseIntentDeriver 映射到 UseStage，特例直接配置 stageOverride。
 *
 * ## 迁移说明
 * 原 enum class PurposeTag 已迁移为 [PurposeTagId] 值对象。
 * 旧引用可直接替换为 PurposeTagId.XXX。
 *
 * @see PurposeTagId 实际的用途标签值对象
 */
@Deprecated(
    message = "使用 PurposeTagId 替代",
    replaceWith = ReplaceWith("PurposeTagId", "lin.bean.usePlan.PurposeTagId")
)
typealias PurposeTag = PurposeTagId
