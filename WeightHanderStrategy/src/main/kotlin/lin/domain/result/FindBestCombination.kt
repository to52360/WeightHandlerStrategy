package lin.domain.result

import lin.bean.ComboCard
import lin.bean.equivalentCostValue
import lin.bean.groupIds
import lin.bean.usePlan.CardComboEntry
import lin.domain.context.comboPenalty
import lin.domain.context.remainingCostPenalty
import lin.domain.context.tacticalContribution
import lin.utils.DecisionLog

interface FindBestCombination {
    fun findBestCombination(targetList: List<ComboCard>, ableCost: Int): List<ComboCard>
}

object DefaultFindBestCombination : FindBestCombination {
    override fun findBestCombination(targetList: List<ComboCard>, ableCost: Int): List<ComboCard> {
        var bestCombination: List<ComboCard> = emptyList()
        var maxEffectiveScore = Double.NEGATIVE_INFINITY

        // ===== 预计算：启动期已按 comboId 归并，直接取用即可 =====
        data class CardBindings(
            val entries: List<CardComboEntry>,
            val groupIds: Set<String>
        )
        val cardsBindings: List<CardBindings> = targetList.map { card ->
            CardBindings(
                entries = card.comboEntries,
                groupIds = card.groupIds()
            )
        }

        // ===== 增量回溯状态 =====
        val currentCombination = mutableListOf<ComboCard>()

        // T-PV-007：配分标尺双最优——同批维护「含核心（combo 定义 core 侧）最优」与「不含核心最优」，
        // 回溯结束输出差值（决策仍全量 max，本跟踪只记日志不改决策。核心分侧依据 = coreGroupIds，D-005/Q-014）。
        var maxWithCore = Double.NEGATIVE_INFINITY
        var maxWithoutCore = Double.NEGATIVE_INFINITY

        // comboId -> (ownGroupId -> refCount)，用于 O(1) 级别的 coreMutex 检查
        val coreMutexState = mutableMapOf<String, MutableMap<String, Int>>()

        // groupId -> refCount，用于 O(1) 级别的 counterpart 存在性检查
        val groupIdRefCounts = mutableMapOf<String, Int>()

        /**
         * 检查将一张新卡加入当前组合时的 combo 加分，返回 NaN 表示 coreMutex 剪枝。
         *
         * 启动期已按 comboId 归并，无需要 scoredComboIds 去重，单次遍历即可完成
         * coreMutex 检查和 counterpart 计分。
         */
        fun evaluateNewComboBonus(entries: List<CardComboEntry>): Double {
            if (entries.isEmpty()) return 0.0
            var scoreBonus = 0.0

            for (entry in entries) {
                // coreMutex 硬剪枝
                for (ownGroupId in entry.coreMutexOwnGroupIds) {
                    val inner = coreMutexState[entry.comboId]
                    if (inner != null && inner.keys.any { it != ownGroupId }) {
                        return Double.NaN
                    }
                }

                // 计分（已归并无需去重）
                if (entry.score != 0.0) {
                    if (entry.counterpartGroupIds.any { it in groupIdRefCounts }) {
                        scoreBonus += entry.score
                    }
                }
            }

            return scoreBonus
        }

        /** 将一张卡的状态合并到增量追踪结构中 */
        fun applyCardState(entries: List<CardComboEntry>, groupIds: Set<String>) {
            for (entry in entries) {
                for (ownGroupId in entry.coreMutexOwnGroupIds) {
                    val inner = coreMutexState.getOrPut(entry.comboId) { mutableMapOf() }
                    inner[ownGroupId] = (inner[ownGroupId] ?: 0) + 1
                }
            }
            for (gid in groupIds) {
                groupIdRefCounts[gid] = (groupIdRefCounts[gid] ?: 0) + 1
            }
        }

        /** 回溯：移除一张卡的状态 */
        fun revertCardState(entries: List<CardComboEntry>, groupIds: Set<String>) {
            for (entry in entries) {
                for (ownGroupId in entry.coreMutexOwnGroupIds) {
                    val inner = coreMutexState[entry.comboId]!!
                    val newCount = inner[ownGroupId]!! - 1
                    if (newCount == 0) {
                        inner.remove(ownGroupId)
                        if (inner.isEmpty()) coreMutexState.remove(entry.comboId)
                    } else {
                        inner[ownGroupId] = newCount
                    }
                }
            }
            for (gid in groupIds) {
                val newCount = groupIdRefCounts[gid]!! - 1
                if (newCount == 0) {
                    groupIdRefCounts.remove(gid)
                } else {
                    groupIdRefCounts[gid] = newCount
                }
            }
        }

        fun backtrack(startIndex: Int, currentCost: Int, currentWeight: Double) {
            val remainingCost = ableCost - currentCost
            val penalty = remainingCostPenalty(remainingCost, ableCost) + comboPenalty(currentCombination.size)
            val effectiveScore = currentWeight - penalty

            if (effectiveScore > maxEffectiveScore) {
                maxEffectiveScore = effectiveScore
                bestCombination = currentCombination.toList()
            }
            // T-PV-007：按「组合是否含任一 combo 的 core 侧卡」分桶更新双最优
            val containsCore = currentCombination.any { it.comboEntries.any { e -> e.coreGroupIds.isNotEmpty() } }
            if (effectiveScore > maxWithCore && containsCore) maxWithCore = effectiveScore
            if (effectiveScore > maxWithoutCore && !containsCore) maxWithoutCore = effectiveScore

            for (i in startIndex until targetList.size) {
                val card = targetList[i]
                if (card.cost() <= remainingCost) {
                    val bindings = cardsBindings[i]
                    val comboBonus = evaluateNewComboBonus(bindings.entries)
                    if (comboBonus.isNaN()) continue

                    // 基础价值不衰减（D-013 方案 A）：物理价值恒定，堆砌惩罚统一由 comboPenalty 承担
                    // D-FO-005 A-合流版（T-FO-014）：combo 分与 ts 同量纲（费），须经 costValue 差分换算成分才能与
                    // baseValue（分）相加。**E 取受益卡**——加分落在「使组合成立的这张卡」（counterpart 已在场、
                    // 本卡补位的那一侧），故取 card 自己的等效费；extPowerWeight 内含的树分/光环分已在评估侧换算，
                    // 此处只换算 comboBonus 一项，无双重换算。
                    val comboContribution =
                        tacticalContribution(card.equivalentCostValue(), comboBonus)
                    val cardIncrementalWeight =
                        card.baseValue + card.extPowerWeight + comboContribution
                    // 前进
                    currentCombination.add(card)
                    applyCardState(bindings.entries, bindings.groupIds)

                    backtrack(
                        startIndex = i + 1,
                        currentCost = currentCost + card.cost(),
                        currentWeight = currentWeight + cardIncrementalWeight
                    )

                    // 回溯
                    currentCombination.removeAt(currentCombination.size - 1)
                    revertCardState(bindings.entries, bindings.groupIds)
                }
            }
        }

        backtrack(0, 0, 0.0)
        logComboGap(maxWithCore, maxWithoutCore)
        return bestCombination
    }

    /**
     * T-PV-007（play-value-model）：combo 配分差值日志——「含核心最优 vs 不含核心最优，差多少」。
     *
     * 只记日志不改决策；供配分标尺（Q-014 锚定诊断）看差值定分——含核心更优（差值正）说明核心值得跟出，
     * 负值说明强拉核心反而亏。置 [lin.utils.DecisionLog] 开关输出（默认关）。
     */
    private fun logComboGap(maxWithCore: Double, maxWithoutCore: Double) {
        if (!DecisionLog.enabled) return
        if (maxWithCore == Double.NEGATIVE_INFINITY || maxWithoutCore == Double.NEGATIVE_INFINITY) return
        DecisionLog.log {
            "组合差值(配分标尺): 含核心最优=$maxWithCore vs 不含核心最优=$maxWithoutCore" +
                    " -> 差值=${maxWithCore - maxWithoutCore}（正值=核心加入更赚，负值=反而更差）"
        }
    }
}
