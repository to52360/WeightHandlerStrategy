package lin.utils

import lin.myLog


/**
 * 对 [kotlin.runCatching] 的封装，自动在失败时记录异常日志（含堆栈）。
 *
 * 用法:
 * ```
 * runCatchingLog("加载规则项失败") { ruleRegistry.leafUiItems() }
 *     .getOrDefault(emptyList())
 * ```
 */
inline fun <T> runCatchingLog(message: String? = null, block: () -> T): Result<T> {
    return runCatching(block).onFailure { e ->
        myLog.error(e) { message ?: "异常: ${e.message}" }
    }
}
