package lin.ui.tree_config

import javafx.scene.control.TreeItem
import lin.rule.tree.EvaluatorTreeConfig
import lin.rule.tree.LogicNode
import lin.tree_config.bridge.formatNodeLabel

enum class LogicNodeType { AND, OR, NOT, BRANCH, LEAF }

class LogicNodeWrapper<L>(
    var type: LogicNodeType,
    var payload: L? = null,
    val titleResolver: ((L?) -> String)? = null,
    /** 用户自定义名称，非空时优先于自动推导的名称显示 */
    var customName: String? = null,
    /** 子节点数量（AND/OR 用于默认名），由 TreeModelConverter 自动设置 */
    var childrenCount: Int = 0
) {
    override fun toString(): String {
        val payloadName = titleResolver?.invoke(payload) ?: payload?.toString() ?: "?"
        val defaultLabel = formatNodeLabel(type.name, childrenCount, payloadName)
        return if (!customName.isNullOrBlank()) {
            "$defaultLabel - [$customName]"
        } else {
            defaultLabel
        }
    }
}

object TreeModelConverter {
    fun <L> toTreeItem(
        node: LogicNode<L>,
        titleResolver: ((L?) -> String)? = null
    ): TreeItem<LogicNodeWrapper<L>> {
        return buildTreeItem(node, titleResolver)
    }

    private fun <L> buildTreeItem(
        node: LogicNode<L>,
        titleResolver: ((L?) -> String)?
    ): TreeItem<LogicNodeWrapper<L>> {
        val customName = node.nodeName
        val item = TreeItem<LogicNodeWrapper<L>>()
        item.isExpanded = true

        when (node) {
            is LogicNode.And -> {
                item.value = LogicNodeWrapper(LogicNodeType.AND, titleResolver = titleResolver, customName = customName)
                node.children.forEach { child ->
                    item.children.add(buildTreeItem(child, titleResolver))
                }
            }
            is LogicNode.Or -> {
                item.value = LogicNodeWrapper(LogicNodeType.OR, titleResolver = titleResolver, customName = customName)
                node.children.forEach { child ->
                    item.children.add(buildTreeItem(child, titleResolver))
                }
            }
            is LogicNode.Not -> {
                item.value = LogicNodeWrapper(LogicNodeType.NOT, titleResolver = titleResolver, customName = customName)
                item.children.add(buildTreeItem(node.child, titleResolver))
            }
            is LogicNode.Branch -> {
                item.value =
                    LogicNodeWrapper(LogicNodeType.BRANCH, node.payload, titleResolver, customName = customName)
                item.children.add(buildTreeItem(node.onTrue, titleResolver))
                item.children.add(buildTreeItem(node.onFalse, titleResolver))
            }
            is LogicNode.Leaf -> {
                item.value = LogicNodeWrapper(LogicNodeType.LEAF, node.payload, titleResolver, customName = customName)
            }
        }
        item.value.childrenCount = item.children.size
        return item
    }

    fun <L> fromTreeItem(
        item: TreeItem<LogicNodeWrapper<L>>,
        emptyPayloadFactory: () -> L
    ): LogicNode<L> {
        val wrapper = item.value
        return when (wrapper.type) {
            LogicNodeType.AND -> LogicNode.And(
                item.children.map { fromTreeItem(it, emptyPayloadFactory) },
                wrapper.customName
            )

            LogicNodeType.OR -> LogicNode.Or(
                item.children.map { fromTreeItem(it, emptyPayloadFactory) },
                wrapper.customName
            )
            LogicNodeType.NOT -> LogicNode.Not(
                if (item.children.isNotEmpty()) fromTreeItem(item.children[0], emptyPayloadFactory)
                else LogicNode.Leaf(emptyPayloadFactory()),
                wrapper.customName
            )

            LogicNodeType.BRANCH -> LogicNode.Branch(
                wrapper.payload ?: emptyPayloadFactory(),
                if (item.children.isNotEmpty()) fromTreeItem(item.children[0], emptyPayloadFactory) else LogicNode.Leaf(
                    emptyPayloadFactory()
                ),
                if (item.children.size > 1) fromTreeItem(item.children[1], emptyPayloadFactory) else LogicNode.Leaf(
                    emptyPayloadFactory()
                ),
                wrapper.customName
            )

            LogicNodeType.LEAF -> LogicNode.Leaf(wrapper.payload ?: emptyPayloadFactory(), wrapper.customName)
        }
    }

}

data class ConfigListItem(
    val id: String,
    val name: String,
    val description: String? = null,
    val bindingIds: String,
    val config: EvaluatorTreeConfig?,
    val isDraft: Boolean = false,
    val enabled: Boolean = true,
    val managerId: String? = null
) {
    override fun toString(): String = when {
        isDraft -> "* $name (未保存)"
        else -> name
    }
}
