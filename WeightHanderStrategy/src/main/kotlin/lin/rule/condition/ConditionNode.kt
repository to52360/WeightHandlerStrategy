package lin.rule.condition

import lin.rule.tree.LogicNode

/**
 * 条件树的业务层负载（Payload）
 */
sealed interface ConditionPayload {
    data class ConditionRef(
        val conditionId: String,
        val args: Map<String, Any> = emptyMap()
    ) : ConditionPayload
}

/**
 * 专属于条件树的通用逻辑节点
 */
typealias ConditionNode = LogicNode<ConditionPayload>
