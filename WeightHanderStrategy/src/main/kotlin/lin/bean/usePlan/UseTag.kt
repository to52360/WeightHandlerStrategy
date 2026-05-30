package lin.bean.usePlan

/**
 * 出牌执行标签，用于描述一张牌在编排和执行阶段的行为特征。
 *
 * 这里的标签只服务 UseIntent/UsePlan，不对规则层开放宏观用途判断。
 * 规则和评估树需要的战略语义应放到 [PurposeTag]。
 */
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
     *
     * 如果规则也需要判断“解场用途”，使用 [PurposeTag.CLEAN]。
     */
    CLEAN,

    /**
     * combo 核心牌。
     * 它是 combo 的发动点，但不代表一定先打。
     *
     * 暂时保留为编排侧标签；如果后续 ComboPlanDefinition 足够表达核心/依赖关系，
     * 再考虑从这里移除。
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
