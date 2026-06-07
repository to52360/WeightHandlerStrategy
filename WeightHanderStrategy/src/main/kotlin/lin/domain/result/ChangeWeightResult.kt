package lin.domain.result

import club.xiaojiawei.hsscriptcardsdk.bean.Card
import lin.bean.ComboCard
import lin.bean.cardExt.base.changeWeight
import lin.myLog

/**
 * 起手换牌结果处理外壳。
 *
 * 选择逻辑由 ChangeCardSelector 纯计算完成；本类只负责记录结果并修改 SDK 传入的 cards 集合。
 */
class ChangeWeightResult(
    private val cards: HashSet<Card>,
    private val comboCards: List<ComboCard>,
    private val keepCost: Int = 2
) {

    fun processChangeCard() {
        val decision = ChangeCardSelector.select(comboCards, keepCost)

        if (decision.keepCards.isEmpty()) {
            myLog.info { "起手换牌: 没有需要保留的卡牌，全部换掉" }
            cards.clear()
            return
        }

        remove(decision.removeCards)
    }

    private fun remove(removeCards: Set<ComboCard>) {
        myLog.info {
            val info = StringBuffer("移除的卡牌:")
            removeCards.forEach { card ->
                info.append("{id:${card.cardId()},换牌权重:${card.changeWeight()}}")
            }
            info.toString()
        }

        removeCards.forEach {
            cards.remove(it.card)
        }
    }
}
