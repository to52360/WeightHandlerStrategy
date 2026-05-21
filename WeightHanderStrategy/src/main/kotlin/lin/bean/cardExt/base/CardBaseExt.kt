package lin.bean.cardExt.base

import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import lin.bean.ComboCard
import lin.config.CardType
import lin.domain.context.NotWeight

fun hasWorth(it: Card): Boolean {
    return it.isAura || it.isTriggerVisual || it.isTitan
}
fun ComboCard.isMinion() = card.isMinion()
fun Card.isMinion() = cardType == CardTypeEnum.MINION
fun ComboCard.atc() = card.atc


fun ComboCard.isCardType(cardType: CardType): Boolean {
    return cardWeightInfo?.isCardType(cardType) ?: false
}

//combo相关
fun ComboCard.weightRules() = cardWeightInfo?.weightRules
fun ComboCard.intentEvaluatorRoots() = cardWeightInfo?.intentEvaluatorRoots

fun ComboCard.changeWeight(): Double = cardWeightInfo?.changeWeight ?: NotWeight

