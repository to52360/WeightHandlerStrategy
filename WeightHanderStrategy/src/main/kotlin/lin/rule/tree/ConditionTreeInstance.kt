package lin.rule.tree

import lin.rule.build.RuleLogic
import lin.rule.registry.RuleRegistry

data class ConditionTreeInstance(
    val bindIds: List<Double>,
    val root: ConditionInstanceNode
)

sealed interface ConditionInstanceNode {
    data class RuleNode(
        val nodeId: String,
        val ruleConfig: RuleConfig,
        val ruleLogic: RuleLogic
    ) : ConditionInstanceNode

    data class AndNode(val children: List<ConditionInstanceNode>) : ConditionInstanceNode
    data class OrNode(val children: List<ConditionInstanceNode>) : ConditionInstanceNode
    data class NotNode(val child: ConditionInstanceNode) : ConditionInstanceNode
}

class ConditionTreeInstantiator(
    private val ruleRegistry: RuleRegistry
) {
    fun instantiate(config: ConditionTreeConfig): ConditionTreeInstance {
        fun instantiateNode(node: ConditionNode): ConditionInstanceNode {
            return when (node) {
                is ConditionNode.RuleNode -> {
                    val ruleConfig = config.ruleConfigs[node.nodeId]
                        ?: error("RuleConfig not found for nodeId=${node.nodeId}")
                    ConditionInstanceNode.RuleNode(
                        nodeId = node.nodeId,
                        ruleConfig = ruleConfig,
                        ruleLogic = ruleRegistry.build(ruleConfig)
                    )
                }

                is ConditionNode.AndNode -> ConditionInstanceNode.AndNode(node.children.map(::instantiateNode))
                is ConditionNode.OrNode -> ConditionInstanceNode.OrNode(node.children.map(::instantiateNode))
                is ConditionNode.NotNode -> ConditionInstanceNode.NotNode(instantiateNode(node.child))
            }
        }

        return ConditionTreeInstance(
            bindIds = config.bindIds,
            root = instantiateNode(config.root)
        )
    }
}
