package lin.rule.context

import lin.myLog
import lin.rule.context.WarView.Companion.ATTENTION_AVG_ATC

private const val ONE_FACTOR = 10

fun WarView.excessDamageFactor(): Int {
    if (ableAtcSum == 0) return excessDamage * ONE_FACTOR
    return excessDamage * ONE_FACTOR / ableAtcSum
}

fun WarView.overLimitByDamage(limit: Int = meBlood * 2): Boolean {
    return excessDamage - me.sumAtc >= limit
}

fun WarView.excessDamageFactorByMeAtc(): Int {
    return (excessDamage - me.sumAtc) * ONE_FACTOR / ableAtcSum
}

fun WarView.isAdvByMeAtc(ableAtcSum: Int = this.ableAtcSum): Boolean {
    val isAdv = isAdvByMeAtcLog(ableAtcSum)
    myLog.info { "是否有优势:$isAdv" }
    return isAdv
}

fun WarView.isAdvByMeAtcLog(ableAtcSum: Int = this.ableAtcSum): Boolean {
    if (excessDamage * 2 > meBlood) return false
    if (rival.sumAtc <= ableAtcSum) return true
    if (rival.sumAtc - me.sumAtc <= ableAtcSum) return true
    if (excessDamage - me.sumAtc <= ableAtcSum) return true
    return false
}

fun WarView.isAdv(ableAtcSum: Int = this.ableAtcSum): Boolean {
    return excessDamage < ableAtcSum
}

fun WarView.compareAvgAct(avgAtc: Int = ATTENTION_AVG_ATC): Boolean {
    return rival.sumAtc < avgAtc * rival.cards.size
}
