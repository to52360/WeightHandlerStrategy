package lin.rule.condition

import lin.rule.orthogonal.TransformCall
import lin.rule.tree.LogicNode

/**
 * 条件树的业务层负载（Payload）
 */
sealed interface ConditionPayload {
    val refId: String
    val args: Map<String, Any>

    data class ConditionRef(
        val conditionId: String,
        override val refId: String = conditionId,
        override val args: Map<String, Any> = emptyMap()
    ) : ConditionPayload

    data class PipelineRef(
        val sourceId: String,
        val transforms: List<TransformCall> = emptyList(),
        val operatorId: String,
        val operatorArgs: Map<String, Any> = emptyMap(),
        val crossCard: Boolean = false,
        override val refId: String
    ) : ConditionPayload {
        override val args: Map<String, Any> get() = emptyMap()
    }
}

/**
 * 专属于条件树的通用逻辑节点
 */
typealias ConditionNode = LogicNode<ConditionPayload>

/**
 * 递归收集条件树中所有叶子/分支节点引用的 ConditionPayload，按 refId 去重（调用端去重）。
 */
fun ConditionNode.collectConditionRefs(): List<ConditionPayload> {
    return when (this) {
        is LogicNode.Leaf -> listOf(payload)

        is LogicNode.And -> children.flatMap { it.collectConditionRefs() }
        is LogicNode.Or -> children.flatMap { it.collectConditionRefs() }
        is LogicNode.Not -> child.collectConditionRefs()
        is LogicNode.Branch -> {
            listOf(payload) + onTrue.collectConditionRefs() + onFalse.collectConditionRefs()
        }
    }
}
