package lin.warExt.action


import club.xiaojiawei.hsscriptbasestrategy.util.DeckStrategyUtil
import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import lin.domain.MyWarManage
import lin.domain.WarInfo
import lin.myLog
import lin.warExt.my.twoLambda.hasCanAttack

/**
 * 怕战场管理太多代码,功能性代码移到这里
 */

/**
 * 使用地标
 */
fun MyWarManage.activeLocation(){
    val cards = war.me.playArea.cards
    try {
        cards.forEach { card ->
            if (card.cardType === CardTypeEnum.LOCATION && !card.isLocationActionCooldown) {
                card.action.lClick()
            }
        }
    } catch (_: ConcurrentModificationException) {
        myLog.info { "并发修改错误重新执行" }
        activeLocation()
    }

}


/**
 * 清场
 */
fun WarInfo.cleanPlay() {
    if (hasCanAttack()) {
        DeckStrategyUtil.cleanPlay()
    }
}

/**
 * 清场
 */
fun WarInfo.cleanPlayAll() {
    var num = 0
    while (hasCanAttack() && num < 2) {
        DeckStrategyUtil.cleanPlay()
        num++
    }
}

/**
 * 发射星剑
 */
fun MyWarManage.useLaunch(){
    val me = war.me
    me.playArea.cards.toList().forEach { card: Card ->
        if (card.isLaunchpad && me.usableResource >= card.launchCost()) {
            card.action.launch()
        }
    }
}
/**
 * 使用技能参考
 * club.xiaojiawei.strategy.HsCommonDeckStrategy.executeOutCard
 */
fun MyWarManage.usePower(){
    val me = war.me
//        使用技能
    me.playArea.power?.let {
        if (it.cost == 0 || me.usableResource >= it.cost) {
            it.action.power()
        }
    }
}
