package lin.warExt.my.twoLambda

import club.xiaojiawei.hsscriptcardsdk.bean.Card
import lin.domain.WarInfo
import lin.warExt.my.base.getPlayCards
import lin.warExt.my.base.hero


//两/三层函数嵌套

inline fun WarInfo.findMeByPlayArea(predicate: (Card) -> Boolean): List<Card> {
    return getPlayCards().filter { predicate(it) }
}

fun WarInfo.findMeTauntByPlayArea(): List<Card> {
    return findMeByPlayArea { it.isTaunt }
}

fun WarInfo.findMeTauntSumBlood(): Int {
    return findMeTauntByPlayArea().sumOf { it.blood() }
}

fun WarInfo.findAtcSum(): Int {
    return getPlayCards().sumOf { it.atc }
}
fun WarInfo.minionHasCanAttack(): Boolean {
    return getPlayCards().any { it.canAttack() }
}

fun WarInfo.hasCanAttack() = minionHasCanAttack() || hero()?.canAttack() ?: false