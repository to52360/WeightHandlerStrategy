package lin.weightHandler

import club.xiaojiawei.hsscriptcardsdk.bean.Card
import lin.bean.CardCombinedConfig
import lin.bean.cardExt.base.isMinion
import lin.domain.context.costValue

/**
 * 基础价值（固有物理价值）三分流计算（D-013 统一叠加模型的"物理基底" + D-014 费用价值凹函数）：
 * 1. 配置等效费用（weightInfo.powerWeight > 0）→ costValue(配置费用) 凹函数换算；
 * 2. 无配置随从 → costValue((实时atc+实时health)/2) + 特征固定分（实时身材凹化）；
 * 3. 无配置法术 → costValue(数据库初始费用)（初始费用凹化，非减费后实时费用）。
 *
 * 纯函数，构造期一次性计算（MyWarManage.parseComboCard 调用），结果存 ComboCard.baseValue，
 * 与运行时战术分（extPowerWeight）正交叠加。
 */
fun calcBaseValue(card: Card, combinedConfig: CardCombinedConfig?, baseCost: Int): Double {
    val configCost = combinedConfig?.weightInfo?.powerWeight ?: 0.0
    return when {
        configCost > 0.0 -> costValue(configCost)
        card.isMinion() -> costValue((card.atc + card.health).toDouble() / 2.0) + getFixedTraitWeight(card)
        else -> costValue(baseCost.toDouble())
    }
}

/**
 * 随从固定特征权重（绝对分，不参与凹函数，不与 cost 相乘）
 */
fun getFixedTraitWeight(card: Card): Double {
    var value = 0.0
    if (card.isDeathRattle) value += 0.5
    if (card.isTaunt) value += 0.5
    if (card.isAdjacentBuff) value += 1.0
    if (card.isAura) value += 1.0
    if (card.isWindFury) value += 0.5
    if (card.isMegaWindfury) value += 1.5
    if (card.isTriggerVisual) value += 0.5
    if (card.isPoisonous) value += 1.0
    value += card.spellPower * 0.5
    return value
}

// 兼容旧接口别名
fun getWeigh(card: Card): Double = getFixedTraitWeight(card)
