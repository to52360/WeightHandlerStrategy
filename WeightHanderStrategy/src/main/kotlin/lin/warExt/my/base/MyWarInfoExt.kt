package lin.warExt.my.base


import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.enums.CardActionEnum
import club.xiaojiawei.hsscriptcardsdk.enums.CardEffectTypeEnum
import lin.domain.WarInfo
import lin.myLog
import lin.serviceLoader.weightRule.hand.HandArea
import lin.warExt.rival.rivalCardsByPlayArea
import kotlin.random.Random


fun WarInfo.getPlayCards(): List<Card> {
    return war.me.playArea.cards
}
fun WarInfo.getPlayCardSize(): Int {
    return getPlayCards().size
}
fun WarInfo.hasWeapon() = war.me.playArea.weapon != null

fun WarInfo.hasArmor(): Boolean {
    hero()?.let {
        return getArmor(it) > 0
    }
    return false
}

fun getArmor(card: Card?) = card?.armor ?: 0


fun WarInfo.hero() = war.me.playArea.hero

fun WarInfo.meBlood() = hero()?.blood() ?: 0
fun WarInfo.getHandCards(): List<Card> {
    return war.me.handArea.cards
}
fun WarInfo.deckArea(): List<Card> {
    return war.me.deckArea.cards
}
fun WarInfo.playCardIsFull(): Boolean {
    return getPlayCards().size == 7
}
fun WarInfo.getPower() = war.me.playArea.power

fun WarInfo.isPower(card: Card): Boolean = getPower() == card

fun WarInfo.getCost() = getNowCost() + extCost

fun WarInfo.getResource() = war.me.resources

fun WarInfo.getNowCost() = war.me.usableResource
fun WarInfo.resource() = war.me.resources

fun WarInfo.hasCost() = getCost() > 0

fun WarInfo.autoPower(card: Card): Boolean {
    val rivalHasPoint = rivalCardsByPlayArea().any { it.canHurt() }
    if (rivalHasPoint) {
        myLog.info { "指向对手随从" }
        val useResult = CardActionEnum.POINT_RIVAL_MINION.playExec(card, CardEffectTypeEnum.UNKNOWN, war)
        if (useResult) {
            return true
        }
    }
    val meHasPoint = getPlayCards().any { it.canHurt() }
    if (meHasPoint) {
        myLog.info { "指向自己随从" }
        val useResult = CardActionEnum.POINT_MY_MINION.playExec(card, CardEffectTypeEnum.UNKNOWN, war)
        if (useResult) {
            return true
        }
    }
    return false
}

fun Card.useCard(card: Card?): Boolean {
    return card?.let { this.area is HandArea && this.action.power(it) == null } ?: false
}

fun <T> List<T>.randomSelectOrNull(): T? {
    if (this.isEmpty()) return null
    return this[Random.nextInt(this.size)]
}
