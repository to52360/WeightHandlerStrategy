package lin.rule.tree

// 如需扩展,采用注册解释器模式
sealed interface ConditionNode {
    data class RuleNode(val nodeId: String) : ConditionNode
    data class AndNode(val children: List<ConditionNode>) : ConditionNode
    data class OrNode(val children: List<ConditionNode>) : ConditionNode
    data class NotNode(val child: ConditionNode) : ConditionNode
    data class BranchNode(
        val nodeId: String,
        val onTrue: ConditionNode,
        val onFalse: ConditionNode
    ) : ConditionNode
}
