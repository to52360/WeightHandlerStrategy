package lin.weightHandler

import club.xiaojiawei.hsscriptcardsdk.bean.Card
import lin.bean.ComboCard
import lin.bean.cardExt.base.isMinion
import lin.domain.MyWarManage
import lin.domain.context.NotWeight
import lin.domain.context.UnUseWeight
import lin.myLog

/**
 * 通用随从权重计算,怎么分开还没有考虑好
 */
class GeneralMinionWeightHandler : WeightHandler, DiscoverWeightHandler {

    private val cache  = hashMapOf<String,Double>()
    override fun cardWeightCompute(callCard: ComboCard, warManage: MyWarManage): Double {
        val result = processPlayFull(callCard, warManage)
        return if (result == UnUseWeight) {
            result
        } else {
            cardWeight(callCard)
        }
    }
    override fun cardWeight(comboCard: ComboCard): Double {
        val card = comboCard.card
        if (comboCard.isBaseWeight() && card.isMinion()) {
            val traitFixed = cache.getOrPut(card.cardId) {
                val weight = getFixedTraitWeight(card)
                myLog.info {
                    "${card.entityName}的特征固定权重:${weight}"
                }
                weight
            }
            // 双轨制兜底模型（D-012）：
            // 随从身材绝对战力 = 1.5 * (攻 + 血) / 2 + 特征固定分
            val statTotal = 1.5 * (card.atc + card.health).toDouble() / 2.0 + traitFixed
            // 扣除已在 ComboCard 注入的 baseScore 轻量微调底分，使得最终兜底总分精确等于身材战力
            val baseWeight = (statTotal - comboCard.baseScore).coerceAtLeast(0.0)
            myLog.info {
                "${card.entityName}通用随从身材兜底计算: statTotal=${statTotal}, netAddWeight=${baseWeight}"
            }
            return baseWeight
        }

        return NotWeight
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
/**
 * 随从固定特征权重（绝对分，不与 cost 相乘）
 */
fun getFixedTraitWeight(card: Card): Double {
    var value = 0.0

    if (card.isDeathRattle) {
        value += 0.5
    }
    if (card.isTaunt) {
        value += 0.5
    }
    if (card.isAdjacentBuff) {
        value += 1.0
    }
    if (card.isAura) {
        value += 1.0
    }
    if (card.isWindFury) {
        value += 0.5
    }
    if (card.isMegaWindfury) {
        value += 1.5
    }
    if (card.isTriggerVisual) {
        value += 0.5
    }
    if (card.isPoisonous) {
        value += 1.0
    }
    value += card.spellPower * 0.5
    return value
}

// 兼容旧接口别名
fun getWeigh(card: Card): Double = getFixedTraitWeight(card)