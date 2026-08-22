package lin.bean.usePlan

/**
 * 运行时可更新的 purposeTags 持有类。
 *
 * 注册为 Koin 单例，消费者通过 [get] 注入：
 * ```
 * val store = get<PurposeTagStore>()
 * val tags = store.tags[cardId]
 * ```
 *
 * ## 写入策略（ - 后续评估）
 *
 * 当前采用 `var + 不可变 Map`：运行时更新通过替换整个引用完成。
 * 写入方式：`store.tags = store.tags + ("cardId" to setOf(PurposeTagId.CLEAN))`
 *
 * 后续需评估：
 * - 是否需要提供便捷的增删方法（put / remove / putAll）
 * - 是否需要线程安全（当前单线程场景不需要）
 * - 写入点分散时是否需要封装为接口约束
 */
class PurposeTagStore(
    var tags: Map<String, Set<PurposeTagId>> = emptyMap()
)
