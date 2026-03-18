package lin.domain.use

import club.xiaojiawei.hsscriptcardsdk.bean.area.HandArea
import club.xiaojiawei.hsscriptcardsdk.data.CARD_DATA_TRIE
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import lin.bean.ComboCard
import lin.domain.MyWarManage
import lin.domain.WarInfo
import lin.domain.context.NotWeight
import lin.myLog
import lin.warExt.my.base.*

/**
 * todo-future 使用最后一张的情况处理不了(无法判断是否有变更),会有问题
 */
fun WarInfo.isChangeByUseSuccess(useCard: () -> ComboCard?): Boolean {
    val beginCard = getHandCards().lastOrNull()
    val expNum = getHandCards().size
    val useResult = useCard() //返回null表示打出失败
    useResult?.let {
        val nowNum = getHandCards().size
        if (nowNum >= expNum) {
            //技能不会被移除的
            return it.card != getPower()
        } else {// 为弃牌写的
            val endCard = getHandCards().lastOrNull()
            if (beginCard != endCard && useResult != beginCard) //排除打出最后一张的情况
                return true
        }
    }

    return false
}

fun MyWarManage.tryUseCard(comboCard: ComboCard): Boolean {
    val card = comboCard.card
    //todo-future 使用unUse来判断是否可用是临时方案,应该统一管理动态变更为不能使用
    //随从已满
    if ((isFull && card.cardType == CardTypeEnum.MINION)) {
        comboCard.unUse()
        return false
    }
    if (useOption(comboCard)) {
        return false
    }

    var useResult = useCard(comboCard)

    if (!useResult && !useOption(comboCard)) {//再次尝试
        //处理战场已满情况
        if (NotWeight == processPlayCardIsFull()) {
            myLog.info { "再次尝试打出" }
            useResult = useCard(comboCard)
        }
        if (!useResult && !(card.cardType == CardTypeEnum.MINION && isFull)) {
            //处理发现
            /*             if (useStrategyUtils.tryAwait()) {
                             //补偿发现动画(主要底层原因,无法使用发现),导致无法打出
                             myLog.info { "发现补偿打出" }
                             var num = 5
                             while (useStrategyUtils.tryAwait() && num > 0) {
                                 comboCard.card.action.chooseOne(0)
                                 num--
                             }
                             useResult = useCard(comboCard)
                         }*/

            myLog.info { "随机指向打出" }
            //todo-future  指向自己没打出也返回ture,card.area !is HandArea是临时方案
            autoPower(card)
            useResult = card.area !is HandArea


        }

    }
    //标记不能打出避免重复尝试
    if (!useResult) comboCard.unUse()
    return useResult
}

/**
 * @return false为符合使用条件
 */
private fun WarInfo.useOption(comboCard: ComboCard): Boolean {
    val card = comboCard.card

    //todo-future 使用unUse来判断是否可用是临时方案,应该统一管理动态变更为不能使用
    if (card.cost > getCost() || comboCard.isUnUse()) {
        return true
    }
    if (card.area !is HandArea) {//区域判断
        if (!isPower(card)) {//技能的处理
            return true
        }

    }
    return false
}

fun useCard(comboCard: ComboCard): Boolean {
    val card = comboCard.card
    val actionInfo = CARD_DATA_TRIE[card.cardId]
    var result = true
    actionInfo?.let {
        card.action.autoPower(it)
    } ?: run {
        result = comboCard.pointCard?.let {
            card.action.power(comboCard.pointCard)?.let { true } ?: false
        } ?: run {
            card.action.power()?.let {
                if (card.isChooseOne) {
                    card.action.chooseOne(0, true)?.let { true } ?: false
                } else true
            } ?: false

        }
    }
    result = result && card.area !is HandArea
    return result

}

fun List<UseBeforeStrategy>.executeAction(card: ComboCard, useDomain: UseDomain) {
    this.forEach {
        it.extAction(card, useDomain)
    }
}

fun List<UseAfterStrategy>.executeAfterAction(card: ComboCard, useDomain: UseDomain) {
    this.forEach {
        it.afterExtAction(card, useDomain)
    }
}