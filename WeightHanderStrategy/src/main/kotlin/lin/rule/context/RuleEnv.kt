package lin.rule.context

import club.xiaojiawei.hsscriptcardsdk.bean.Card
import lin.bean.cardExt.cardList.canHurt
import lin.domain.MatchState
import lin.domain.PipelineCache

interface RuleEnv {
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