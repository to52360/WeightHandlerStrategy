package lin.rule.tree

sealed interface ConditionNode {
    data class RuleNode(val nodeId: String) : ConditionNode
    data class AndNode(val children: List<ConditionNode>) : ConditionNode
    data class OrNode(val children: List<ConditionNode>) : ConditionNode
    data class NotNode(val child: ConditionNode) : ConditionNode
}
