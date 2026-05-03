package lin.tree_config.ui

import javafx.scene.control.TreeItem
import lin.rule.tree.EvaluatorNode
import lin.rule.tree.EvaluatorTreeConfig

enum class NodeType { AND, OR, NOT, RULE, BRANCH }

class EvaluatorNodeWrapper(var type: NodeType, var nodeId: String = "") {
    override fun toString(): String = when (type) {
        NodeType.AND -> "AND"
        NodeType.OR -> "OR"
        NodeType.NOT -> "NOT"
        NodeType.RULE -> "Rule: ${nodeId.ifEmpty { "<未命名>" }}"
        NodeType.BRANCH -> "Branch: ${nodeId.ifEmpty { "<未命名>" }}"
    }
}

object TreeModelConverter {
    fun toTreeItem(node: EvaluatorNode): TreeItem<EvaluatorNodeWrapper> {
        val item = TreeItem<EvaluatorNodeWrapper>()
        item.isExpanded = true
        when (node) {
            is EvaluatorNode.AndNode -> {
                item.value = EvaluatorNodeWrapper(NodeType.AND)
                node.children.forEach { item.children.add(toTreeItem(it)) }
            }

            is EvaluatorNode.OrNode -> {
                item.value = EvaluatorNodeWrapper(NodeType.OR)
                node.children.forEach { item.children.add(toTreeItem(it)) }
            }

            is EvaluatorNode.NotNode -> {
                item.value = EvaluatorNodeWrapper(NodeType.NOT)
                item.children.add(toTreeItem(node.child))
            }

            is EvaluatorNode.BranchNode -> {
                item.value = EvaluatorNodeWrapper(NodeType.BRANCH, node.nodeId)
                item.children.add(toTreeItem(node.onTrue))
                item.children.add(toTreeItem(node.onFalse))
            }

            is EvaluatorNode.RuleNode -> {
                item.value = EvaluatorNodeWrapper(NodeType.RULE, node.nodeId)
            }
        }
        return item
    }

    fun fromTreeItem(item: TreeItem<EvaluatorNodeWrapper>): EvaluatorNode {
        val wrapper = item.value
        return when (wrapper.type) {
            NodeType.AND -> EvaluatorNode.AndNode(item.children.map { fromTreeItem(it) })
            NodeType.OR -> EvaluatorNode.OrNode(item.children.map { fromTreeItem(it) })
            NodeType.NOT -> EvaluatorNode.NotNode(
                if (item.children.isNotEmpty()) fromTreeItem(item.children[0]) else EvaluatorNode.RuleNode("empty")
            )

            NodeType.BRANCH -> EvaluatorNode.BranchNode(
                wrapper.nodeId,
                if (item.children.isNotEmpty()) fromTreeItem(item.children[0]) else EvaluatorNode.RuleNode("empty"),
                if (item.children.size > 1) fromTreeItem(item.children[1]) else EvaluatorNode.RuleNode("empty")
            )

            NodeType.RULE -> EvaluatorNode.RuleNode(wrapper.nodeId.ifEmpty { "empty" })
        }
    }
}

data class ConfigListItem(
    val id: String,
    val name: String,
    val groupId: String,
    val config: EvaluatorTreeConfig?,
    val isDraft: Boolean = false
) {
    override fun toString(): String = if (isDraft) "* $name (未保存)" else name
}
