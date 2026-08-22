package lin.serviceLoader.cardInfoProvide


import club.xiaojiawei.hsscriptcardsdk.data.CARD_DATA_TRIE
import lin.bean.CardWeightInfo
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * D-007 小数位编码 v3 解码结果：等效费（整数，D-014 原语义不含战术溢价）+ 空闲放行门槛（null = 未配置，随时可垫）。
 */
data class DecodedCostValue(
    val equivalentCostValue: Double,
    val surplusIdleThreshold: Int?
)

/**
 * D-007 小数位编码 v3 解码（phase-1 存储侧约定，纯函数）：
 *
 * 上游存储把「等效费 + 空闲放行门槛/10」压在 powerWeight 一个数里（如 5.4 = 等效 5 费 / 空闲 ≥4 放行「3捏4放行」）。
 * 整数（无小数位）= 未配置门槛 → 随时可垫。战术溢价不占本编码位（由评估树树分×scale 运行时折算）。
 *
 * 约束（迁移债，正规载体 = CardWeight.weight，待 T-002/T-003 分组迁移完成后释放）：
 * - 等效费必须整数（3.5 会被解码为 等效3/门槛5）；
 * - 小数位仅 1 位，>9.5 的舍入（如手误 5.99）钳到 9；局面动态调门槛暂无载体（Q-022）。
 */
fun decodeCostValue(rawPowerWeight: Double): DecodedCostValue {
    val equivalentCostValue = floor(rawPowerWeight)
    val fraction = rawPowerWeight - equivalentCostValue
    val surplusIdleThreshold = if (fraction > ENCODING_EPSILON) {
        ((fraction * 10).roundToInt()).coerceIn(1, 9)
    } else {
        null
    }
    return DecodedCostValue(equivalentCostValue, surplusIdleThreshold)
}

private const val ENCODING_EPSILON = 1e-9

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
