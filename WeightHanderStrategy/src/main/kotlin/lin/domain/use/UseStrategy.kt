package lin.domain.use

import lin.domain.context.AwaitAnimationTime
import lin.myLog


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



