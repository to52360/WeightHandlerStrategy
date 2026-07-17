package lin.domain

import lin.lifecycle.GameLifecycle

/**
 * 管道执行缓存（Q-2a 性能优化）。
 *
 * 由 [MatchState.playEventVersion] 驱动失效：打出事件有新写入 → 版本号递增 → 对应缓存键 miss → 重算。
 * 实现 [GameLifecycle]，整局开始时清空所有缓存条目。
 *
 * 后续 Q-2b 等场景可复用：各自写入侧暴露版本号，消费侧组合版本号作为缓存键。
 */
class PipelineCache : GameLifecycle {

    private val store = mutableMapOf<String, Any?>()

    /**
     * 惰性缓存：[key] 未命中时执行 [compute] 并存入。
     * @return 缓存值或 compute 结果
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> getOrCompute(key: String, compute: () -> T): T {
        return store.getOrPut(key) { compute() } as T
    }

    /** 清空全部缓存条目。由 [GameLifecycle.start] 触发，不单独调用。 */
    override fun start() {
        store.clear()
    }
}
