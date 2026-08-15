package lin.weightHandler

import lin.bean.ComboCard
import lin.bean.cardExt.base.isMinion
import lin.domain.MyWarManage
import lin.domain.context.NotWeight

/**
 * 随从可用性判断：战场已满时的清理/禁用处理。
 * 原 GeneralMinionWeightHandler 的"身材基础价值"已抽离到 BaseValueCalculator.calcBaseValue
 * （构造期静态计算，走 ComboCard.baseValue），本 handler 只保留运行时"战场满"这一动态判断。
 */
class PlayFullHandler : WeightHandler {

    override fun cardWeightCompute(callCard: ComboCard, warManage: MyWarManage): Double {
        return processPlayFull(callCard, warManage)
    }

    /**
     * 处理战场已满
     */
    fun processPlayFull(callCard: ComboCard, warManage: MyWarManage): Double {
        if (callCard.isMinion()) {
            return warManage.processPlayCardIsFull()
        }
        return NotWeight
    }
}
