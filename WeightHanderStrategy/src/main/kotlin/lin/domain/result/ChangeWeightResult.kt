package lin.domain.result

import club.xiaojiawei.hsscriptcardsdk.bean.Card
import lin.bean.ComboCard
import lin.config.EngineConfig
import lin.myLog
import lin.utils.CardLogFormat
import lin.utils.DecisionLog
import lin.utils.LogCategory

/**
 * 起手换牌结果处理外壳。
 *
 * 选择逻辑由 ChangeCardSelector 纯计算完成；本类只负责**记录结果**并修改 SDK 传入的 cards 集合。
 *
 * 日志分工（T-FO-011）：
 * - **骨架行恒输出**：一手牌换了几张、门槛 `keepCost` 是多少——这是复盘起手的索引（原先只有
 *   「全部换掉」或「移除的卡牌」两种零散输出，正常换牌时反而几乎无记录）。
 * - **逐卡明细**挂 [LogCategory.CHANGE]**：保留/换掉两份名单，按 cardId 排序保证可对比。
 *   明细**不推断"为何落选"**——那要复刻 [ChangeCardSelector] 的资格/价值双轴判定，等于把决策
 *   逻辑复制出第二份（K-TG-007「同一事实两处组装必然单边漂移」）；判定输入已随骨架行给出，归因由读者完成。
 */
class ChangeWeightResult(
    private val cards: HashSet<Card>,
    private val comboCards: List<ComboCard>,
    private val keepCost: Int = EngineConfig.changeKeepCost
) {

    fun processChangeCard() {
        val decision = ChangeCardSelector.select(comboCards, keepCost)

        myLog.info {
            "起手换牌: 手牌 ${comboCards.size} 张 → 保留 ${decision.keepCards.size} / 换掉 ${decision.removeCards.size}" +
                    "（keepCost=$keepCost）"
        }
        DecisionLog.log(LogCategory.CHANGE) {
            buildString {
                append("起手换牌明细:\n")
                append("  保留:\n").append(CardLogFormat.cards(decision.keepCards)).append('\n')
                append("  换掉:\n").append(CardLogFormat.cards(decision.removeCards))
            }
        }

        if (decision.keepCards.isEmpty()) {
            cards.clear()
            return
        }

        remove(decision.removeCards)
    }

    private fun remove(removeCards: Set<ComboCard>) {
        removeCards.forEach {
            cards.remove(it.card)
        }
    }
}
