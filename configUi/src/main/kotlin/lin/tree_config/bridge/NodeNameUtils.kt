package lin.tree_config.bridge

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/** 将路径列表转为稳定的 key 字符串：path [] → "$", [0,1] → "$0.1" */
fun pathToKey(path: List<Int>): String {
    if (path.isEmpty()) return "$"
    return "$" + path.joinToString(".")
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
