package lin.rule.condition

import lin.rule.tree.LogicNode

/**
 * 条件树的业务层负载（Payload）
 */
sealed interface ConditionPayload {
    data class ConditionRef(
        val conditionId: String,
        val refId: String = conditionId,
        val args: Map<String, Any> = emptyMap()
    ) : ConditionPayload
}

/**
 * 专属于条件树的通用逻辑节点
 */
typealias ConditionNode = LogicNode<ConditionPayload>

/**
 * 递归收集条件树中所有叶子/分支节点引用的 ConditionRef，按 refId 去重。
 */
fun ConditionNode.collectConditionRefs(): List<ConditionPayload.ConditionRef> {
    return when (this) {
        is LogicNode.Leaf -> {
            val ref = payload as? ConditionPayload.ConditionRef
            if (ref != null) listOf(ref) else emptyList()
        }

        is LogicNode.And -> children.flatMap { it.collectConditionRefs() }
        is LogicNode.Or -> children.flatMap { it.collectConditionRefs() }
        is LogicNode.Not -> child.collectConditionRefs()
        is LogicNode.Branch -> {
            val ref = payload as? ConditionPayload.ConditionRef
            val list = if (ref != null) listOf(ref) else emptyList()
            list + onTrue.collectConditionRefs() + onFalse.collectConditionRefs()
        }
    }
}
