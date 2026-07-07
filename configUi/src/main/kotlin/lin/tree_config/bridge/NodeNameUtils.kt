package lin.tree_config.bridge

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import lin.rule.tree.LogicNode

/** 将路径列表转为稳定的 key 字符串：path [] → "$", [0,1] → "$0.1" */
fun pathToKey(path: List<Int>): String {
    if (path.isEmpty()) return "$"
    return "$" + path.joinToString(".")
}

/**
 * 节点名称格式化（唯一一份模板，UI 和 MCP 共用）。
 * [typeKey] 用 "AND"/"OR"/"NOT"/"BRANCH"/"LEAF"。
 */
fun formatNodeLabel(typeKey: String, childrenCount: Int, payloadName: String): String = when (typeKey) {
    "AND" -> "AND ($childrenCount)"
    "OR" -> "OR ($childrenCount)"
    "NOT" -> "NOT"
    "BRANCH" -> "BRANCH"
    "LEAF" -> "LEAF"
    else -> typeKey
}

/**
 * 为任意 [LogicNode] 派生默认的人类可读名称。
 * 委托给 [formatNodeLabel]。
 */
fun <L> defaultNodeName(node: LogicNode<L>, payloadName: (L) -> String): String {
    val typeKey: String
    val count: Int
    val name: String
    when (node) {
        is LogicNode.And -> {
            typeKey = "AND"; count = node.children.size; name = ""
        }

        is LogicNode.Or -> {
            typeKey = "OR"; count = node.children.size; name = ""
        }

        is LogicNode.Not -> {
            typeKey = "NOT"; count = 0; name = ""
        }

        is LogicNode.Branch -> {
            typeKey = "BRANCH"; count = 0; name = payloadName(node.payload)
        }

        is LogicNode.Leaf -> {
            typeKey = "LEAF"; count = 0; name = payloadName(node.payload)
        }
    }
    return formatNodeLabel(typeKey, count, name)
}

private val nodeNamesMapper = jacksonObjectMapper()

/** 解析 node_names JSON 字符串为 Map */
fun parseNodeNames(json: String?): Map<String, String> {
    if (json.isNullOrBlank()) return emptyMap()
    return try {
        @Suppress("UNCHECKED_CAST")
        nodeNamesMapper.readValue(json, Map::class.java) as? Map<String, String> ?: emptyMap()
    } catch (e: Exception) {
        emptyMap()
    }
}
