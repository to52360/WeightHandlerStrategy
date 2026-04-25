package lin.rule.tree

// 如需扩展,采用注册解释器模式
sealed interface EvaluatorNode {
    data class RuleNode(val nodeId: String) : EvaluatorNode
    data class AndNode(val children: List<EvaluatorNode>) : EvaluatorNode
    data class OrNode(val children: List<EvaluatorNode>) : EvaluatorNode
    data class NotNode(val child: EvaluatorNode) : EvaluatorNode
    data class BranchNode(
        val nodeId: String,
        val onTrue: EvaluatorNode,
        val onFalse: EvaluatorNode
    ) : EvaluatorNode
}
