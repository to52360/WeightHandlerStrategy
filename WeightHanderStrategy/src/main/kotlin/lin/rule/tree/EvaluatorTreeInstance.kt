package lin.rule.tree

import lin.rule.build.RuleLogic
import lin.rule.registry.RuleRegistry

data class EvaluatorTreeInstance(
    val bindGroupIds: List<String>,
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

class EvaluatorTreeInstantiator(
    private val ruleRegistry: RuleRegistry
) {
    fun instantiate(config: EvaluatorTreeConfig): EvaluatorTreeInstance {
        fun instantiateNode(node: EvaluatorNode): EvaluatorInstanceNode {
            return when (node) {
                is LogicNode.Leaf -> {
                    when (val payload = node.payload) {
                        is EvaluatorPayload.Rule -> {
                            val ruleConfig = config.ruleConfigs[payload.nodeId]
                                ?: error("RuleConfig not found for nodeId=${payload.nodeId}")
                            EvaluatorInstanceNode.RuleNode(
                                nodeId = payload.nodeId,
                                ruleLogic = ruleRegistry.build(ruleConfig)
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
                    val ruleConfig = config.ruleConfigs[payload.nodeId]
                        ?: error("RuleConfig not found for nodeId=${payload.nodeId}")
                    EvaluatorInstanceNode.BranchNode(
                        nodeId = payload.nodeId,
                        condition = ruleRegistry.build(ruleConfig),
                        onTrue = instantiateNode(node.onTrue),
                        onFalse = instantiateNode(node.onFalse)
                    )
                }
            }
        }

        return EvaluatorTreeInstance(
            bindGroupIds = config.bindGroupIds,
            root = instantiateNode(config.root)
        )
    }
}
