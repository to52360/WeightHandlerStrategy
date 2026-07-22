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
     * 评估级缓存：同一评估周期（单次决策 pass）内共享，随 RuleEnv 回收。
     * 由 [lin.rule.condition.PipelineAssembler] 闭包层按需自动调用，用于基于内容哈希的 Source 与 Transform 分段缓存。
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