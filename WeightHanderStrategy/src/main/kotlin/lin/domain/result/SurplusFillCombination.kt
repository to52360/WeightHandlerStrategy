package lin.domain.result

import lin.bean.ComboCard
import lin.bean.surplusFillValue

/**
 * 填充参与地板（D-007 费量纲，Q-036 具名化）：fillValue ≤ 地板 = 垫出的费单位机会成本非正（真亏），
 * 不参与填充搜索。0 的语义 = 「不亏才垫」；若要调整宽松度应动 [lin.domain.context.TacticalScoreScale]
 * 或逐卡 N 门槛，而非本地板。
 */
private const val FILL_VALUE_FLOOR = 0.0

/**
 * D-007 余费填充搜索（T-015）：目标函数 `max Σ surplusFillValue`（费单位机会成本），
 * powerWeight 仅作平局 tie-break——与主搜索 [DefaultFindBestCombination] 的分数目标（baseValue+ext+comboBonus−penalty）
 * 彻底分离，余费阶段不再用 baseValue 一维择优。
 *
 * 与主搜索器的差异（phase-1 有意为之）：
 * - 不携带 combo 机制（coreMutex 剪枝 / counterpart comboBonus）：comboBonus 是分单位，与费单位 fillValue 不可混加；
 *   填充池内互斥/conflict 一致性留二期（跨池互斥本就无检查）。
 * - 不带 comboPenalty / remainingCostPenalty：fillValue 本身就是机会成本算术（垃圾牌 fillValue≈0 无堆砌激励），
 *   资源经济职责已从惩罚项移交填充层（D-007）。
 * - fillValue ≤ [FILL_VALUE_FLOOR] 的牌不参与（软死捏：硬死捏走评估树 Banned / isUnUse 候选门）。
 */
object SurplusFillCombination : FindBestCombination {

    override fun findBestCombination(targetList: List<ComboCard>, ableCost: Int): List<ComboCard> {
        val candidates = targetList.filter { it.surplusFillValue() > FILL_VALUE_FLOOR }
        if (candidates.isEmpty()) return emptyList()

        var bestCombination: List<ComboCard> = emptyList()
        var bestFillValue = 0.0
        var bestTieBreak = Double.NEGATIVE_INFINITY

        val currentCombination = mutableListOf<ComboCard>()

        fun backtrack(startIndex: Int, remainingCost: Int, fillValueSum: Double, tieBreakSum: Double) {
            if (fillValueSum > bestFillValue || (fillValueSum == bestFillValue && tieBreakSum > bestTieBreak)) {
                bestFillValue = fillValueSum
                bestTieBreak = tieBreakSum
                bestCombination = currentCombination.toList()
            }

            for (i in startIndex until candidates.size) {
                val card = candidates[i]
                if (card.cost() <= remainingCost) {
                    currentCombination.add(card)
                    backtrack(
                        startIndex = i + 1,
                        remainingCost = remainingCost - card.cost(),
                        fillValueSum = fillValueSum + card.surplusFillValue(),
                        tieBreakSum = tieBreakSum + card.baseValue + card.extPowerWeight
                    )
                    currentCombination.removeAt(currentCombination.size - 1)
                }
            }
        }

        backtrack(0, ableCost, 0.0, 0.0)
        return bestCombination
    }
}
