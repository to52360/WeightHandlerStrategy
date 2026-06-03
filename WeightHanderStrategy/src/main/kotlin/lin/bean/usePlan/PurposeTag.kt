package lin.bean.usePlan

/**
 * 战略用途标签，用于规则和评估树进行宏观决策评估。
 *
 * 这里的标签只描述“为什么这张牌值得被选中”，不描述真实出牌顺序。
 * 出牌默认顺序由 UseIntentDeriver 映射到 UseStage，特例直接配置 stageOverride。
 */
enum class PurposeTag {
    /**
     * 保命牌。
     * 用于低血量或防御回合的决策。
     */
    SAVE_LIFE,

    /**
     * 解场/清场牌。
     */
    CLEAN,

    /**
     * 贪婪/成长/蓄力牌。
     */
    GREED,

    /**
     * 斩杀/收尾牌。
     */
    FINISH,

    /**
     * 普通价值牌。
     */
    VALUE,

    /**
     * 额外费用牌。
     */
    EXTRA_COST
}
