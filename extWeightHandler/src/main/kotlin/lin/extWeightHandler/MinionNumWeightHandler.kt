package lin.extWeightHandler

import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import lin.bean.ComboCard
import lin.domain.MyWarManage
import lin.domain.WarInfo
import lin.domain.context.CostWeight
import lin.domain.context.NotWeight
import lin.lifecycle.RoundLifecycle
import lin.warExt.my.base.getCost
import lin.warExt.my.base.getPlayCardSize
import lin.weightHandler.WeightHandler

/**
 *
 * AuraBoost 广播重构
 * 场面太多随从,费用低的减权重
 * todo-future 逻辑不够严谨 需要"之后处理器"(WeightHandlerAfter)才行,之后处理器还没想好怎么实现
 * [lin.weightHandler.WeightHandlerAfter]
 *
 */
class MinionNumWeightHandler : WeightHandler, RoundLifecycle {
    var tooMach = false
    val maxCostGap = 2
    val gapCostWeight = -CostWeight

    override fun cardWeightCompute(callCard: ComboCard, warManage: MyWarManage): Double {
        if (tooMach && callCard.card.cardType == CardTypeEnum.MINION) {
            val costGap = warManage.getCost() - callCard.cost()
            if (costGap >= maxCostGap) {

                return gapCostWeight - (costGap / 10.0)

            }

        }
        return NotWeight
    }

    override fun start(warInfo: WarInfo) {
        val maxMinionNum = 4
        tooMach = warInfo.getPlayCardSize() > maxMinionNum
    }
}