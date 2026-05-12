package lin.rule.tree

/**
 * 评估树的业务层负载（Payload）
 */
sealed interface EvaluatorPayload {
    data class Rule(val nodeId: String) : EvaluatorPayload
    data class BranchCondition(val nodeId: String) : EvaluatorPayload
}

/**
 * 向下兼容并特化出评估树的通用逻辑节点
 */
typealias EvaluatorNode = LogicNode<EvaluatorPayload>
