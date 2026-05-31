package lin.bean


import lin.domain.combo.ComboParse.Companion.BEFORE
import lin.domain.context.NotWeight
import lin.domain.context.OrderWeight
import lin.myLog

/**
 * @param depIds 数据库用String,用","分割
 * todo-future comboWeight多种语义(对扩展和维护有麻烦,暂时没空整理,主要没兴趣了)
 * @param comboWeight  当换牌只用到正负,负数互斥 ,最后打出作为打出顺序使用,作为combo,==0表示不符合条件
 */
class ComboInfo(
    val infoId: Int,
    val bindId: Double,
    val comboType: String,
    val depIds: Array<Double>,
    val comboWeight: Double
)

/**
 *@param comboId todo-future  comboId 不知道有没有用了
 * @param comboType todo-future 感觉可以删除了
 *
 */
@Deprecated("combo已重构这里准备删除,迁移到ComboEntries")
open class Combo(val comboId: Int, val comboRule: ComboRule, val comboType: String) {
    open fun comboProcess(callComboCard: ComboCard, comboCard: ComboCard): Double {
        return comboRule.let {
            val weight = it(comboCard)
            //优先级处理
            if (weight != NotWeight) {//表示是同一组
                val beforeWeight = callComboCard.powerWeight
                if (BEFORE == comboType) {//如果是before会增加排序权重
                    //之前策略
                    if (beforeWeight <= comboCard.powerWeight) {//增加权重
                        val addWeight = comboCard.powerWeight - beforeWeight + OrderWeight //保证同组优先级最高
                        callComboCard.addWeight(addWeight)
                    }
                    if (callComboCard.powerWeight < comboCard.powerWeight) {
                        myLog.warn { "${comboId}的组核心权重应该大于成员权重,可现在核心权重${callComboCard.powerWeight},成员权重${comboCard.powerWeight}" }
                    }

                }
            }
            weight

        }

    }
}



