package lin.domain.use

import lin.domain.context.AwaitAnimationTime

/**
 * 使用动作注册表：动作标识 → 工厂函数。
 * 配置侧只存标识（如 "RECORD_PLAY"）+ 动态属性 extraConfig，引擎侧工厂函数解析为 UseStrategy 对象。
 */
object UseActionRegistry {
    private val registry: Map<String, (Map<String, Any>) -> UseStrategy> = mapOf(
        "RECORD_PLAY" to { cfg -> RecordPlayAction(cfg) },
        "AWAIT_ANIMATION" to { _ -> AwaitAnimationStrategy(AwaitAnimationTime) }
    )

    fun resolve(actionId: String, extraConfig: Map<String, Any> = emptyMap()): UseStrategy =
        registry[actionId]?.invoke(extraConfig) ?: error("未知的使用动作标识: $actionId")

    fun knownActionIds(): Set<String> = registry.keys
}
