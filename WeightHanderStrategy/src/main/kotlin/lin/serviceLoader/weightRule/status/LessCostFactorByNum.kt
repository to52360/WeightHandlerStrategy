package lin.serviceLoader.weightRule.status

import lin.bean.ComboCard
import lin.domain.WarInfo
import lin.serviceLoader.weightRule.utils.abs.AbsWeightCondition
import lin.warExt.my.base.getCost

/**
 * 用于使用过牌卡牌
 *  存在问题 一直使用过牌
 *
 */
class LessCostFactorByNum : AbsWeightCondition() {
    override fun description(): String {
        return "剩余费用卡牌倍率"
    }
    override fun calculateWeight(callCard: ComboCard, warInfo: WarInfo): Double {

        return if (warInfo.getCost() >= callCard.cost() * number) groupWeight
        else unConditionWeight
    }
}