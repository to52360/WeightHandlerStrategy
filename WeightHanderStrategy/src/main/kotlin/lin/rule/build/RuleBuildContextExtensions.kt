package lin.rule.build

/**
 * 从 leafConfig.args 中按 key 取出单个字段，适用于不值得单独建模的零散动态字段。
 *
 * 用法（固定结构 + 动态字段混用）：
 * ```kotlin
 * val fixed = parseArgs<FixedConfig>()
 * val threshold = parseArg<Int>("threshold") ?: 10
 * ```
 *
 * 注意：类型转换为软转换（as?），取不到或类型不匹配时返回 null，调用方自行处理默认值。
 */
inline fun <reified V> RuleBuildContext<*>.parseArg(key: String): V? =
    leafConfig.args[key] as? V
