package lin.domain.result

import lin.bean.ComboCard
import lin.bean.usePlan.CardComboEntry
import lin.domain.context.CostWeight

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
                entries = card.comboEntries(),
                groupIds = card.groupIds()
            )
        }

        // ===== 增量回溯状态 =====
        val currentCombination = mutableListOf<ComboCard>()

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
            val penalty = remainingCost * CostWeight
            val effectiveScore = currentWeight - penalty

            if (effectiveScore > maxEffectiveScore) {
                maxEffectiveScore = effectiveScore
                bestCombination = currentCombination.toList()
            }

            for (i in startIndex until targetList.size) {
                val card = targetList[i]
                if (card.cost() <= remainingCost) {
                    val bindings = cardsBindings[i]
                    val comboBonus = evaluateNewComboBonus(bindings.entries)
                    if (comboBonus.isNaN()) continue

                    // 前进
                    currentCombination.add(card)
                    applyCardState(bindings.entries, bindings.groupIds)

                    backtrack(
                        startIndex = i + 1,
                        currentCost = currentCost + card.cost(),
                        currentWeight = currentWeight + card.powerWeight + comboBonus
                    )

                    // 回溯
                    currentCombination.removeAt(currentCombination.size - 1)
                    revertCardState(bindings.entries, bindings.groupIds)
                }
            }
        }

        backtrack(0, 0, 0.0)
        return bestCombination
    }
}
