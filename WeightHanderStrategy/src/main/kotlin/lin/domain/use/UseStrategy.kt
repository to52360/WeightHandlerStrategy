package lin.domain.use

import lin.domain.MatchState
import lin.domain.context.AwaitAnimationTime
import lin.myLog
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
        myLog.info { "等待地标动画" }
        Thread.sleep(AwaitAnimationTime)
        val comboCard = context.card
        comboCard.card.action.lClick()
        useDomain.registerExpectedDiscover(context)
        useDomain.awaitExpectedDiscover(context)
        //避免没点到
        myLog.info { "再点一下" }
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
object AwaitAnimationStrategy : UseAfterStrategy {
    override fun afterExtAction(
        context: UseContext,
        useDomain: UseDomain
    ) {
        context.extraAwaitMillis = AwaitAnimationTime
    }

}

/**
 * 打出记录动作（D-2）：声明式 opt-in，只有配置了此动作的卡牌才记录。
 * 通过 Koin 直接注入 MatchState，不经过 UseDomain 中转。
 * 接入：在卡牌的 [lin.config.UseConfig.useStrategyList] 中加入 [RecordPlayAction] 即可。
 */
object RecordPlayAction : UseAfterStrategy, KoinComponent {
    private val matchState: MatchState by inject()

    override fun afterExtAction(context: UseContext, useDomain: UseDomain) {
        matchState.recordCardPlayed(context.card)
    }
}



