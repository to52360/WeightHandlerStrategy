package lin.domain.use.plan

enum class UseTag {
    /**
     * 资源牌。
     * 例如硬币、减费、临时水晶。
     */
    RESOURCE,

    /**
     * 过牌牌。
     * 打出后可能改变手牌，需要重新规划。
     */
    DRAW,

    /**
     * 清场或解场牌。
     * 打出后会改变战场，需要重新评估场面。
     */
    CLEAN,

    /**
     * combo 核心牌。
     * 它是 combo 的发动点，但不代表一定先打。
     */
    COMBO_CORE,

    /**
     * combo 依赖牌。
     * 它是 combo 的配合牌，但不代表一定后打。
     */
    COMBO_DEP,

    /**
     * 使用后必须重新规划。
     * 例如过牌、发现、清场、召唤后影响场面。
     */
    REPLAN_AFTER_USE
}
