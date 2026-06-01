package lin.bean.usePlan

/**
 * 使用阶段只表达“默认顺序”，不能表达强制关系。
 * 强制先后顺序要放到 [UseConstraint]。
 */
enum class UseStage {
    RESOURCE,
    SETUP,
    CLEAR,
    DEFEND,
    COMBO,
    GENERAL,
    END
}
