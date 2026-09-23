package lin.domain.use

import lin.bean.ComboCard
import lin.domain.MyWarManage
import lin.domain.context.ChangeAnimationTime
import lin.domain.context.FourAnimationTime
import lin.domain.context.UseAnimationTime
import lin.myLog
import lin.utils.CardLogFormat
import lin.utils.DecisionLog
import lin.utils.LogCategory
import lin.warExt.my.base.getCost
import lin.warExt.my.base.getHandCards

/**
 * 直接阻塞实现就好了,用来控制并发
 */
class UseDomain(val warManage: MyWarManage) {
    private val discoverSync = DiscoverSync()

    fun useCard(card: ComboCard): UseCardResult {
        val context = UseContext(card)
        try {
            context.stateChanged = warManage.isChangeByUseSuccess {
                card.useBeforeStrategy?.executeAction(context, this)
                if (context.replanRequested) {
                    return@isChangeByUseSuccess null
                }
                context.useSucceeded = warManage.tryUseCard(card)
                myLog.info { "打出 ${CardLogFormat.card(card)} → ${if (context.useSucceeded) "成功" else "失败"}" }
                if (context.useSucceeded && card.useIntent?.replanAfterUse == true)
                    context.replanRequested = true
                card.useAfterStrategy?.executeAfterAction(context, this)
                if (context.replanRequested) {
                    return@isChangeByUseSuccess null
                }
                if (context.useSucceeded) {
                    //超过指定测试应该不要等待时间了
                    DecisionLog.log(LogCategory.ANIM) { "等待打出动画 ${UseAnimationTime + context.extraAwaitMillis}ms" }
                    // 增加等待时间看看效果
                    Thread.sleep(UseAnimationTime + context.extraAwaitMillis)
                    //select 暂时这样处理发现,看一下有没有问题
                    discoverSync.waitFallbackIfNeeded()
                    card
                } else null
            }
        } finally {
            context.discoverTicket?.close()
            context.discoverTicket = null
        }
        //todo-future 临时方案 重新查暂定也用change的方案
        if (context.replanRequested) {
            context.stateChanged = true
        }
        if (context.stateChanged) {
            if (!context.replanRequested) {
                // 骨架：状态确实变了（复盘关心）；动画等待归 ANIM 分类（刷屏无判读价值）。
                myLog.info { "状态变化 → 重新查询 combo" }
                DecisionLog.log(LogCategory.ANIM) { "等待变化动画 ${ChangeAnimationTime}ms" }
                Thread.sleep(ChangeAnimationTime)
            }
            val existAbleUse = warManage.getHandCards().any { it.cost <= warManage.getCost() }
            //没有可用牌就不再查了
            if (!existAbleUse) {
                myLog.info { "无可用牌不执行重新查找combo" }
                context.stateChanged = false
            }
        }
        context.shouldReplan = context.stateChanged
        return context.toResult()
    }


    fun registerExpectedDiscover(context: UseContext) {
        context.discoverTicket = discoverSync.registerExpectedDiscover()
    }

    fun awaitExpectedDiscover(context: UseContext) {
        if (!context.useSucceeded) return

        val ticket = context.discoverTicket ?: return
        DecisionLog.log(LogCategory.ANIM) { "进入同步，等待发现（上限 ${FourAnimationTime}ms）" }
        ticket.await(FourAnimationTime)
        DecisionLog.log(LogCategory.ANIM) { "发现同步结束，等待发现动画 ${ChangeAnimationTime}ms" }
        //等待发现动画
        Thread.sleep(ChangeAnimationTime)
        ticket.close()
        context.discoverTicket = null
    }

    fun onSdkChooseCompleted() {
        discoverSync.onSdkChooseCompleted()
    }

}
