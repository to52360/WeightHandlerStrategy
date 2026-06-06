package lin.rule.tree

import lin.rule.build.RuleLogic

data class EvaluatorTreeInstance(
    val bindings: List<EvaluatorTreeBinding>,
    val root: EvaluatorInstanceNode
)

sealed interface EvaluatorInstanceNode {
    data class RuleNode(
        val nodeId: String,
        val ruleLogic: RuleLogic
    ) : EvaluatorInstanceNode

    data class AndNode(val children: List<EvaluatorInstanceNode>) : EvaluatorInstanceNode
    data class OrNode(val children: List<EvaluatorInstanceNode>) : EvaluatorInstanceNode
    data class NotNode(val child: EvaluatorInstanceNode) : EvaluatorInstanceNode
    data class BranchNode(
        val nodeId: String,
        val condition: RuleLogic,
        val onTrue: EvaluatorInstanceNode,
        val onFalse: EvaluatorInstanceNode
    ) : EvaluatorInstanceNode
}

/**
 * 函数式架构核心：
 * 把对依赖的解析交还给顶层，本文件只提供纯粹的转换逻辑（从 JSON 数据转换为运行时对象）。
 *
 * @param leafBuilder 高阶函数，表示如何将一个具体的叶子配置转为真正的可执行 RuleLogic 闭包
 */
fun EvaluatorTreeConfig.instantiate(
    leafBuilder: (EvaluatorLeafConfig) -> RuleLogic
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
                            ruleLogic = leafBuilder(leafConfig)
                        )
                    }

                    is EvaluatorPayload.BranchCondition -> error("BranchCondition Leaf is unexpected here.")
                }
            }

            is LogicNode.And -> EvaluatorInstanceNode.AndNode(node.children.map(::instantiateNode))
            is LogicNode.Or -> EvaluatorInstanceNode.OrNode(node.children.map(::instantiateNode))
            is LogicNode.Not -> EvaluatorInstanceNode.NotNode(instantiateNode(node.child))
            is LogicNode.Branch -> {
                val payload = node.payload as? EvaluatorPayload.BranchCondition
                    ?: error("Branch node payload must be BranchCondition")
                val leafConfig = this.leafConfigs[payload.nodeId]
                    ?: error("EvaluatorLeafConfig not found for nodeId=${payload.nodeId}")
                EvaluatorInstanceNode.BranchNode(
                    nodeId = payload.nodeId,
                    condition = leafBuilder(leafConfig),
                    onTrue = instantiateNode(node.onTrue),
                    onFalse = instantiateNode(node.onFalse)
                )
            }
        }
    }

    return EvaluatorTreeInstance(
        bindings = this.bindings,
        root = instantiateNode(this.root)
    )
}
