package lin.ui.tree_config.ui

import javafx.scene.control.TreeItem
import lin.rule.tree.EvaluatorTreeConfig
import lin.rule.tree.LogicNode
import lin.tree_config.bridge.pathToKey

enum class LogicNodeType { AND, OR, NOT, BRANCH, LEAF }

class LogicNodeWrapper<L>(
    var type: LogicNodeType,
    var payload: L? = null,
    val titleResolver: ((L?) -> String)? = null,
    /** 用户自定义名称，非空时优先于自动推导的名称显示 */
    var customName: String? = null
) {
    override fun toString(): String = when {
        !customName.isNullOrBlank() -> customName!!
        type == LogicNodeType.AND -> "AND"
        type == LogicNodeType.OR -> "OR"
        type == LogicNodeType.NOT -> "NOT"
        type == LogicNodeType.BRANCH -> "Branch: " + (titleResolver?.invoke(payload) ?: payload?.toString()
        ?: "<未命名>")

        else -> "Leaf: " + (titleResolver?.invoke(payload) ?: payload?.toString() ?: "<未命名>")
    }
}

object TreeModelConverter {
    fun <L> toTreeItem(
        node: LogicNode<L>,
        titleResolver: ((L?) -> String)? = null,
        nodeNames: Map<String, String> = emptyMap()
    ): TreeItem<LogicNodeWrapper<L>> {
        return buildTreeItem(node, titleResolver, nodeNames, mutableListOf())
    }

    private fun <L> buildTreeItem(
        node: LogicNode<L>,
        titleResolver: ((L?) -> String)?,
        nodeNames: Map<String, String>,
        path: MutableList<Int>
    ): TreeItem<LogicNodeWrapper<L>> {
        val key = pathToKey(path)
        val customName = nodeNames[key]
        val item = TreeItem<LogicNodeWrapper<L>>()
        item.isExpanded = true

        when (node) {
            is LogicNode.And -> {
                item.value = LogicNodeWrapper(LogicNodeType.AND, titleResolver = titleResolver, customName = customName)
                node.children.forEachIndexed { i, child ->
                    path.add(i)
                    item.children.add(buildTreeItem(child, titleResolver, nodeNames, path))
                    path.removeAt(path.lastIndex)
                }
            }
            is LogicNode.Or -> {
                item.value = LogicNodeWrapper(LogicNodeType.OR, titleResolver = titleResolver, customName = customName)
                node.children.forEachIndexed { i, child ->
                    path.add(i)
                    item.children.add(buildTreeItem(child, titleResolver, nodeNames, path))
                    path.removeAt(path.lastIndex)
                }
            }
            is LogicNode.Not -> {
                item.value = LogicNodeWrapper(LogicNodeType.NOT, titleResolver = titleResolver, customName = customName)
                path.add(0)
                item.children.add(buildTreeItem(node.child, titleResolver, nodeNames, path))
                path.removeAt(path.lastIndex)
            }
            is LogicNode.Branch -> {
                item.value =
                    LogicNodeWrapper(LogicNodeType.BRANCH, node.payload, titleResolver, customName = customName)
                path.add(0)
                item.children.add(buildTreeItem(node.onTrue, titleResolver, nodeNames, path))
                path.removeAt(path.lastIndex)
                path.add(1)
                item.children.add(buildTreeItem(node.onFalse, titleResolver, nodeNames, path))
                path.removeAt(path.lastIndex)
            }
            is LogicNode.Leaf -> {
                item.value = LogicNodeWrapper(LogicNodeType.LEAF, node.payload, titleResolver, customName = customName)
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

    /**
     * 从树形 UI 的 [TreeItem] 提取所有用户自定义节点名称，返回 pathKey → name 映射。
     * 用于保存时收集 customName 写入 node_names 持久化列。
     */
    fun <L> extractCustomNames(rootItem: TreeItem<LogicNodeWrapper<L>>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        collectCustomNames(rootItem, mutableListOf(), result)
        return result
    }

    private fun <L> collectCustomNames(
        item: TreeItem<LogicNodeWrapper<L>>,
        path: MutableList<Int>,
        result: MutableMap<String, String>
    ) {
        val name = item.value.customName
        if (!name.isNullOrBlank()) {
            result[pathToKey(path)] = name
        }
        item.children.forEachIndexed { i, child ->
            path.add(i)
            collectCustomNames(child, path, result)
            path.removeAt(path.lastIndex)
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
    val managerId: String? = null,
    /** 节点自定义名称的 JSON 字符串（Map<String,String>），从 DB node_names 列加载 */
    val nodeNames: String? = null
) {
    override fun toString(): String = when {
        isDraft -> "* $name (未保存)"
        else -> name
    }
}
