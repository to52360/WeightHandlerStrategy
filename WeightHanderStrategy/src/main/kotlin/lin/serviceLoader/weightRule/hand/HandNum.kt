package lin.serviceLoader.weightRule.hand

import lin.bean.ComboCard
import lin.bean.cardExt.base.isMinion
import lin.domain.WarInfo
import lin.serviceLoader.weightRule.utils.abs.AbsWeightCondition
import lin.warExt.my.base.getHandCards

/**
 * 手牌数量决定权重
 */
class HandNumWeight : AbsWeightCondition() {
    override fun calculateWeight(callCard: ComboCard, warInfo: WarInfo): Double {
        return if (warInfo.getHandCards().size > number) groupWeight else unConditionWeight
    }
}

abstract class AbsNumWeight(val filterList: (WarInfo) -> Int) : AbsWeightCondition() {
    override fun description(): String {
        return "根据数量"
    }
    override fun calculateWeight(callCard: ComboCard, warInfo: WarInfo): Double {
        val size = filterList(warInfo)
        return if (size > number) {
            groupWeight
        } else unConditionWeight
    }

}

class DefHandNum : AbsNumWeight({
    it.getHandCards().size
})

class HandNumMinion : AbsNumWeight({
    it.getHandCards().count { it.isMinion() }
})
class HandNumMinionByTaunt : AbsNumWeight({
    it.getHandCards().count { it.isMinion() && it.isTaunt }
})
