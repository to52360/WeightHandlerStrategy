package lin.rule.context

import club.xiaojiawei.hsscriptcardsdk.bean.Card
import lin.bean.cardExt.cardList.canHurt

interface RuleEnv {
    fun warView(): WarView
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