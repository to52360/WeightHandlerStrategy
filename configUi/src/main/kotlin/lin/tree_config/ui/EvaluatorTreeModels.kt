package lin.tree_config.ui

import javafx.scene.control.TreeItem
import lin.rule.tree.EvaluatorTreeConfig
import lin.rule.tree.LogicNode

enum class LogicNodeType { AND, OR, NOT, BRANCH, LEAF }

class LogicNodeWrapper<L>(
    var type: LogicNodeType,
    var payload: L? = null,
    val titleResolver: ((L?) -> String)? = null
) {
    override fun toString(): String = when (type) {
        LogicNodeType.AND -> "AND"
        LogicNodeType.OR -> "OR"
        LogicNodeType.NOT -> "NOT"
        LogicNodeType.BRANCH -> "Branch: " + (titleResolver?.invoke(payload) ?: payload?.toString() ?: "<未命名>")
        LogicNodeType.LEAF -> "Leaf: " + (titleResolver?.invoke(payload) ?: payload?.toString() ?: "<未命名>")
    }
}

object TreeModelConverter {
    fun <L> toTreeItem(
        node: LogicNode<L>,
        titleResolver: ((L?) -> String)? = null
    ): TreeItem<LogicNodeWrapper<L>> {
        val item = TreeItem<LogicNodeWrapper<L>>()
        item.isExpanded = true
        when (node) {
            is LogicNode.And -> {
                item.value = LogicNodeWrapper(LogicNodeType.AND, titleResolver = titleResolver)
                node.children.forEach { item.children.add(toTreeItem(it, titleResolver)) }
            }

            is LogicNode.Or -> {
                item.value = LogicNodeWrapper(LogicNodeType.OR, titleResolver = titleResolver)
                node.children.forEach { item.children.add(toTreeItem(it, titleResolver)) }
            }

            is LogicNode.Not -> {
                item.value = LogicNodeWrapper(LogicNodeType.NOT, titleResolver = titleResolver)
                item.children.add(toTreeItem(node.child, titleResolver))
            }

            is LogicNode.Branch -> {
                item.value = LogicNodeWrapper(LogicNodeType.BRANCH, node.payload, titleResolver)
                item.children.add(toTreeItem(node.onTrue, titleResolver))
                item.children.add(toTreeItem(node.onFalse, titleResolver))
            }

            is LogicNode.Leaf -> {
                item.value = LogicNodeWrapper(LogicNodeType.LEAF, node.payload, titleResolver)
            }
        }
        return item
    }

    fun <L> fromTreeItem(
        item: TreeItem<LogicNodeWrapper<L>>,
        emptyPayloadFactory: () -> L
    ): LogicNode<L> {
        val wrapper = item.value
        return when (wrapper.type) {
            LogicNodeType.AND -> LogicNode.And(item.children.map { fromTreeItem(it, emptyPayloadFactory) })
            LogicNodeType.OR -> LogicNode.Or(item.children.map { fromTreeItem(it, emptyPayloadFactory) })
            LogicNodeType.NOT -> LogicNode.Not(
                if (item.children.isNotEmpty()) fromTreeItem(item.children[0], emptyPayloadFactory)
                else LogicNode.Leaf(emptyPayloadFactory())
            )

            LogicNodeType.BRANCH -> LogicNode.Branch(
                wrapper.payload ?: emptyPayloadFactory(),
                if (item.children.isNotEmpty()) fromTreeItem(item.children[0], emptyPayloadFactory) else LogicNode.Leaf(
                    emptyPayloadFactory()
                ),
                if (item.children.size > 1) fromTreeItem(item.children[1], emptyPayloadFactory) else LogicNode.Leaf(
                    emptyPayloadFactory()
                )
            )

            LogicNodeType.LEAF -> LogicNode.Leaf(wrapper.payload ?: emptyPayloadFactory())
        }
    }
}

data class ConfigListItem(
    val id: String,
    val name: String,
    val bindingIds: String,
    val config: EvaluatorTreeConfig?,
    val isDraft: Boolean = false,
    val enabled: Boolean = true,
    val managerId: String? = null,
    val isTemplate: Boolean = false
) {
    override fun toString(): String = when {
        isTemplate -> "\uD83D\uDCCB $name [模板]"
        isDraft -> "* $name (未保存)"
        else -> name
    }
}
