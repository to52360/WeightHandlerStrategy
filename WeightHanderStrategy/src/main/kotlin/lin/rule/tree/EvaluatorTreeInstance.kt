package lin.rule.tree

import lin.rule.build.LeafLogic
import lin.rule.condition.ConditionLogic

data class EvaluatorTreeInstance(
    val bindingType: EvaluatorTreeBindingType,
    val bindingIds: List<String>,
    val root: EvaluatorInstanceNode
)

sealed interface EvaluatorInstanceNode {
    data class RuleNode(
        val nodeId: String,
        val leafLogic: LeafLogic
    ) : EvaluatorInstanceNode

    data class AndNode(val children: List<EvaluatorInstanceNode>) : EvaluatorInstanceNode
    data class OrNode(val children: List<EvaluatorInstanceNode>) : EvaluatorInstanceNode

    // @verify U-003: 评估树不支持 NOT（取反），配置层 LogicNode.Not 仅用于条件树；
    // instantiate 遇到评估树 NOT 配置直接报错（见下方）
    data class BranchNode(
        val nodeId: String,
        val condition: ConditionLogic,
        val onTrue: EvaluatorInstanceNode,
        val onFalse: EvaluatorInstanceNode
    ) : EvaluatorInstanceNode
}

/**
 * 函数式架构核心：
 * 把对依赖的解析交还给顶层，本文件只提供纯粹的转换逻辑（从 JSON 数据转换为运行时对象）。
 *
 * @param leafBuilder 高阶函数，表示如何将一个具体的叶子配置转为真正的可执行 LeafLogic 闭包
 */
fun EvaluatorTreeConfig.instantiate(
    leafBuilder: (EvaluatorLeafConfig) -> LeafLogic,
    branchConditionBuilder: (EvaluatorLeafConfig) -> ConditionLogic
): EvaluatorTreeInstance {
    fun instantiateNode(node: EvaluatorNode): EvaluatorInstanceNode {
        return when (node) {
            is LogicNode.Leaf -> {
                when (val payload = node.payload) {
                    is EvaluatorPayload.Rule -> {
                        val leafConfig = this.leafConfigs[payload.nodeId]
                            ?: error("EvaluatorLeafConfig not found for nodeId=${payload.nodeId}")
                        EvaluatorInstanceNode.RuleNode(
                            nodeId = payload.nodeId,
                            leafLogic = leafBuilder(leafConfig)
                        )
                    }

                    is EvaluatorPayload.BranchCondition -> error("BranchCondition Leaf is unexpected here.")
                }
            }

            is LogicNode.And -> EvaluatorInstanceNode.AndNode(node.children.map(::instantiateNode))
            is LogicNode.Or -> EvaluatorInstanceNode.OrNode(node.children.map(::instantiateNode))
            // 评估树不支持 NOT（取反），LogicNode.Not 仅用于条件树；此处直接拒绝
            is LogicNode.Not -> error("评估树不支持 NOT 节点（取反仅用于条件树）")
            is LogicNode.Branch -> {
                val payload = node.payload as? EvaluatorPayload.BranchCondition
                    ?: error("Branch node payload must be BranchCondition")
                val leafConfig = this.leafConfigs[payload.nodeId]
                    ?: error("EvaluatorLeafConfig not found for nodeId=${payload.nodeId}")
                EvaluatorInstanceNode.BranchNode(
                    nodeId = payload.nodeId,
                    condition = branchConditionBuilder(leafConfig),
                    onTrue = instantiateNode(node.onTrue),
                    onFalse = instantiateNode(node.onFalse)
                )
            }
        }
    }

    return EvaluatorTreeInstance(
        bindingType = this.bindingType,
        bindingIds = this.bindingIds,
        root = instantiateNode(this.root)
    )
}
