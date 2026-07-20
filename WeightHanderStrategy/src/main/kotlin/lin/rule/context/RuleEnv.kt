package lin.rule.context

import club.xiaojiawei.hsscriptcardsdk.bean.Card
import lin.bean.cardExt.cardList.canHurt
import lin.domain.MatchState
import lin.domain.PipelineCache
import lin.domain.WarInfo

interface RuleEnv {
    /** 战场运行时对象（手牌/战场/墓地卡牌、血量、法力等），归 Env */
    fun warInfo(): WarInfo
    fun warView(): WarView
    fun matchState(): MatchState
    /** 管道执行缓存（Q-2a 性能优化）。新对局时自动清空。 */
    fun pipelineCache(): PipelineCache
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