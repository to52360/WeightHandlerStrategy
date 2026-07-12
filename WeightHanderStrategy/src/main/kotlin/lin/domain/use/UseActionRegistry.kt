package lin.domain.use

/**
 * 使用动作注册表：动作标识（配置侧存储的字符串）→ UseStrategy 对象。
 * 配置侧只存标识（如 "RECORD_PLAY"），引擎侧在此解析为具体动作对象，避免硬编码业务。
 */
object UseActionRegistry {
    private val registry: Map<String, UseStrategy> = mapOf(
        "RECORD_PLAY" to RecordPlayAction
    )

    fun resolve(actionId: String): UseStrategy =
        registry[actionId] ?: error("未知的使用动作标识: $actionId")

    fun knownActionIds(): Set<String> = registry.keys
}
