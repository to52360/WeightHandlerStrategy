package lin.domain.use

import lin.domain.MatchState
import lin.domain.context.AwaitAnimationTime
import lin.utils.DecisionLog
import lin.utils.LogCategory
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject


sealed interface UseStrategy

interface UseAfterStrategy : UseStrategy {
    fun afterExtAction(context: UseContext, useDomain: UseDomain)
}

interface UseBeforeStrategy : UseStrategy {
    fun extAction(context: UseContext, useDomain: UseDomain)
}

object UseAfterLClick : UseAfterStrategy {
    override fun afterExtAction(context: UseContext, useDomain: UseDomain) {
        DecisionLog.log(LogCategory.ANIM) { "等待地标动画 ${AwaitAnimationTime}ms" }
        Thread.sleep(AwaitAnimationTime)
        val comboCard = context.card
        comboCard.card.action.lClick()
        useDomain.registerExpectedDiscover(context)
        useDomain.awaitExpectedDiscover(context)
        //避免没点到
        DecisionLog.log(LogCategory.ANIM) { "再点一下（补点，等 ${AwaitAnimationTime}ms）" }
        Thread.sleep(AwaitAnimationTime)
        comboCard.card.action.lClick()
    }
}

/**
 *发现处理策略
 */
object DiscoverUseStrategy : UseAfterStrategy, UseBeforeStrategy {
    override fun extAction(context: UseContext, useDomain: UseDomain) {
        useDomain.registerExpectedDiscover(context)
    }

    override fun afterExtAction(context: UseContext, useDomain: UseDomain) {
        useDomain.awaitExpectedDiscover(context)
    }

}
class AwaitAnimationStrategy(private val delayMillis: Long) : UseAfterStrategy {
    override fun afterExtAction(
        context: UseContext,
        useDomain: UseDomain
    ) {
        context.extraAwaitMillis = delayMillis
    }

}

/**
 * 打出记录动作（D-2）：声明式 opt-in，只有配置了此动作的卡牌才记录。
 * 通过动态属性 extraConfig.stat_dimensions 声明统计维度，首次出牌时懒注册到 MatchState 并预编译闭包。
 * 不通过 UseDomain 中转，直接 Koin 注入 MatchState。
 */
class RecordPlayAction(config: Map<String, Any> = emptyMap()) : UseAfterStrategy, KoinComponent {
    private val matchState: MatchState by inject()
    private val dimensions: List<MatchState.StatDimension> = parseStatDimensions(config)
    private var registered = false

    override fun afterExtAction(context: UseContext, useDomain: UseDomain) {
        if (!registered) {
            matchState.registerDimensions(dimensions)
            registered = true
        }
        matchState.recordCardPlayed(context.card)
    }

    companion object {
        private const val KEY_STAT_DIMENSIONS = "stat_dimensions"

        private fun parseStatDimensions(config: Map<String, Any>): List<MatchState.StatDimension> {
            val raw = config[KEY_STAT_DIMENSIONS] as? List<*> ?: return emptyList()
            return raw.mapNotNull { item ->
                val map = item as? Map<*, *> ?: return@mapNotNull null
                val keyStr = map["key"] as? String ?: return@mapNotNull null
                val durStr = map["duration"] as? String ?: return@mapNotNull null
                try {
                    MatchState.StatDimension(
                        MatchState.StatDimensionKey.valueOf(keyStr),
                        MatchState.StatDuration.valueOf(durStr)
                    )
                } catch (_: Exception) { null }
            }
        }
    }
}



