package lin.rule.tree

import lin.rule.build.RuleLogic
import lin.rule.registry.RuleRegistry

data class EvaluatorTreeInstance(
    val bindByGroupId: String,
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
                is EvaluatorNode.RuleNode -> {
                    val ruleConfig = config.ruleConfigs[node.nodeId]
                        ?: error("RuleConfig not found for nodeId=${node.nodeId}")
                    EvaluatorInstanceNode.RuleNode(
                        nodeId = node.nodeId,
                        ruleLogic = ruleRegistry.build(ruleConfig)
                    )
                }

                is EvaluatorNode.AndNode -> EvaluatorInstanceNode.AndNode(node.children.map(::instantiateNode))
                is EvaluatorNode.OrNode -> EvaluatorInstanceNode.OrNode(node.children.map(::instantiateNode))
                is EvaluatorNode.NotNode -> EvaluatorInstanceNode.NotNode(instantiateNode(node.child))
                is EvaluatorNode.BranchNode -> {
                    val ruleConfig = config.ruleConfigs[node.nodeId]
                        ?: error("RuleConfig not found for nodeId=${node.nodeId}")
                    EvaluatorInstanceNode.BranchNode(
                        nodeId = node.nodeId,
                        condition = ruleRegistry.build(ruleConfig),
                        onTrue = instantiateNode(node.onTrue),
                        onFalse = instantiateNode(node.onFalse)
                    )
                }
            }
        }

        return EvaluatorTreeInstance(
            bindByGroupId = config.bindByGroupId,
            root = instantiateNode(config.root)
        )
    }
}
