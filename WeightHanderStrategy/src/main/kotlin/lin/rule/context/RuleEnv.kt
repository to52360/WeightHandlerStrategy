package lin.rule.context

import club.xiaojiawei.hsscriptcardsdk.bean.Card
import lin.bean.cardExt.cardList.canHurt
import lin.domain.MatchState
import lin.domain.WarInfo

interface RuleEnv {
    /** 战场运行时对象（手牌/战场/墓地卡牌、血量、法力等），归 Env */
    fun warInfo(): WarInfo
    fun warView(): WarView
    fun matchState(): MatchState

    /**
     * 评估级缓存：同一评估内多规则树共享，评估结束随 RuleEnv 回收。
     * 由组件闭包按需调用，cacheKey 由组件自己拼。
     *
     * ⚠️ 临时基础设施：当前仅 WarViewSource 使用。MatchActivityEventsSource 已改为
     * 引用返回（Map<Kind, List<Card>>），不再需要缓存。后续其他 Source 逐步改为
     * 引用返回后，本方法可废弃。
     */
    fun <T> cache(key: String, compute: () -> T): T
}

data class SideSnapshot(
    val cards: List<Card>,
    val taunt: List<Card>,
    val num: Int,
    val sumAtc: Int,
) {
    companion object {
        fun from(rawCards: List<Card>) = SideSnapshot(
            cards = rawCards.canHurt(),
            taunt = rawCards.filter { it.isTaunt },
            num = rawCards.size,
            sumAtc = rawCards.sumOf { it.atc },
        )
    }
}