package lin.ui.components

/**
 * 为逻辑树编辑器创建空 payload 的工厂策略。
 * 泛型 L 为具体的叶子/分支节点负载类型（如 EvaluatorPayload、ConditionPayload）。
 */
interface PayloadFactory<L> {
    fun createEmptyLeaf(): L
    fun createEmptyBranch(): L
    fun extractNodeId(payload: L): String?
}
