package lin.rule.build

import lin.rule.tree.LogicNode

/**
 * 条件树的业务层负载（Payload）简单占位
 */
sealed interface ConditionPayload {
    data class SimpleExpression(val expression: String) : ConditionPayload
}

/**
 * 专属于条件树的通用逻辑节点
 */
typealias ConditionNode = LogicNode<ConditionPayload>
