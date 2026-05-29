package lin.bean.usePlan

/**
 * 战略用途标签，用于规则条件树进行宏观决策评估。
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
    VALUE
}
