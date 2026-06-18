package lin.rule.orthogonal

/**
 * 转换器调用描述：包含调用的转换器 ID 及其专有的嵌套参数。
 */
data class TransformCall(
    val transformId: String,
    val args: Map<String, Any> = emptyMap()
)
