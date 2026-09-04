package lin.serviceLoader.cardInfoProvide


import club.xiaojiawei.hsscriptcardsdk.data.CARD_DATA_TRIE
import lin.bean.CardWeightInfo
import kotlin.math.abs
import kotlin.math.round

/**
 * D-007 小数位编码 v4 解码结果：等效费（≤1 位小数精度，D-014 原语义不含战术溢价）+ 空闲放行门槛
 * （null = 未配置，随时可垫）。运行时只消费本结构，与存储原值（raw powerWeight）解耦。
 */
data class DecodedCostValue(
    val equivalentCostValue: Double,
    val surplusIdleThreshold: Int?
)

/**
 * D-007 小数位编码 v4 编解码单点（存储侧约定，纯函数；configUi 与引擎共用，勿双实现）：
 *
 * 上游存储把「等效费(≤1 位小数) + 空闲放行门槛 N(百分位)」压在 powerWeight 一个数里，
 * 编解码按"分"做整数运算（round 吸收二进制误差）规避浮点坑：
 * - 3.5  = 等效 3.5 费，未配门槛（随时可垫）
 * - 3.54 = 等效 3.5 费 + 空闲 ≥4 才放行垫牌（读作「3.5 费，门槛 4」）
 * - 5.04 = 等效 5 费 + 门槛 4
 *
 * v4 相对 v3（2026-09-04，sop-rework T-002）：等效费放宽到 0.1 精度（v3 限整数，3.5 会被误读为
 * 等效3/门槛5；v4 下 3.5 = 等效 3.5 费）。⚠️ 迁移债：v3 存量 `x.y`（y=门槛）语义翻转，须改写成 `x.0y`。
 * 战术溢价不占本编码位（由评估树树分×scale 运行时折算）。局面动态调门槛暂无载体（Q-022）。
 */
fun decodeCostValue(rawPowerWeight: Double): DecodedCostValue {
    val cents = round(rawPowerWeight * 100.0).toLong() // 归一到"分"（容忍 5.04/5.4 类二进制误差）
    val equivalentCostValue = (cents / 10) / 10.0      // 丢百分位门槛，还原一位小数等效费
    val thresholdDigits = (cents % 10).toInt()         // 百分位 = 门槛 1..9；0 = 未配置
    val surplusIdleThreshold = if (thresholdDigits in 1..9) thresholdDigits else null
    return DecodedCostValue(equivalentCostValue, surplusIdleThreshold)
}

/** [decodeCostValue] 的逆运算（v4）：把语义字段编码回单数 powerWeight 存储值。 */
fun encodeCostValue(equivalentCost: Double, surplusIdleThreshold: Int?): Double {
    require(equivalentCost >= 0.0) { "等效费用不能为负，当前: $equivalentCost" }
    val eqCents = round(equivalentCost * 10.0).toLong()
    require(abs(equivalentCost - eqCents / 10.0) < 1e-9) {
        "等效费用必须是 0.1 的整数倍（一位小数精度），当前: $equivalentCost"
    }
    require(surplusIdleThreshold == null || surplusIdleThreshold in 1..9) {
        "门槛 N 取值 1..9（null = 未配置），当前: $surplusIdleThreshold"
    }
    val cents = eqCents * 10 + (surplusIdleThreshold ?: 0)
    return cents / 100.0
}

/**
 *  可以考虑这里塞分组信息
 */
class DefCardWeightInfoProvide : CardWeightInfoProvide {
    override fun getInfos(): Map<String, CardWeightInfo> {
        val weightConfigs = CARD_DATA_TRIE.data()
        return weightConfigs.associateBy(
            keySelector = { it.key }
        ) { weightCard ->
            val card = weightCard.value
            val decoded = decodeCostValue(card.powerWeight)
            CardWeightInfo(
                cardId = weightCard.key,
                powerWeight = decoded.equivalentCostValue,
                groupId = card.weight,
                changeWeight = card.changeWeight,
                surplusIdleThreshold = decoded.surplusIdleThreshold
            )
        }
    }
}
