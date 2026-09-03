package lin.domain.result

import lin.bean.ComboCard
import lin.bean.cardExt.base.changeWeight
import lin.bean.usePlan.CardComboEntry
import lin.domain.context.NotWeight

// 临时方案,如果配置多了,封装成环境配置
private const val DEFAULT_KEEP_COST = 2

/**
 * 起手换牌选择结果。
 *
 * keepCards / removeCards 都是基于 ComboCard 的纯计算结果，外层再负责修改 SDK 的 Card 集合。
 */
data class ChangeDecision(
    val keepCards: Set<ComboCard>,
    val removeCards: Set<ComboCard>
)

/**
 * 起手换牌纯选择核心。
 *
 * 只根据 changeWeight 选择保留集合，并复用 CardComboEntry 的 coreMutex 关系做硬约束。
 * 不写日志、不改外部集合、不依赖 Koin，便于独立验证。
 */
object ChangeCardSelector {

    fun select(cards: List<ComboCard>, keepCost: Int = DEFAULT_KEEP_COST): ChangeDecision {
        if (cards.isEmpty()) {
            return ChangeDecision(emptySet(), emptySet())
        }

        val candidates = cards.map { it.toChangeCandidate() }
            .filter { it.isKeepCandidate(keepCost) }
        val bestKeepCards = findBestKeepSet(candidates)
        val keepSet = bestKeepCards.map { it.source }.toSet()

        return ChangeDecision(
            keepCards = keepSet,
            removeCards = cards.filterNot { it in keepSet }.toSet()
        )
    }

    private fun ComboCard.toChangeCandidate(): ChangeCandidate {
        return ChangeCandidate(
            source = this,
            cost = cost(),
            changeWeight = changeWeight(),
            comboEntries = comboEntries
        )
    }

    private fun ChangeCandidate.isKeepCandidate(keepCost: Int): Boolean {
        return if (cost <= keepCost) {
            changeWeight >= NotWeight
        } else {
            changeWeight > NotWeight
        }
    }

    private fun findBestKeepSet(candidates: List<ChangeCandidate>): List<ChangeCandidate> {
        var best: List<ChangeCandidate>? = null

        forEachSubset(candidates) { subset ->
            val currentBest = best
            if (subset.isNotEmpty() &&
                !hasCoreMutexConflict(subset) &&
                (currentBest == null || subset.isBetterThan(currentBest))
            ) {
                best = subset
            }
        }

        val bestKeepCards = best ?: return emptyList()
        return if (bestKeepCards.sumOf { it.changeWeight } >= NotWeight) bestKeepCards else emptyList()
    }

    /**
     * 起手牌数量很小，直接枚举所有保留子集。
     * 先只比较非空集合，最后没有非负收益时回退为空集合，保证全部低分或全不适合时可以全部换掉。
     */
    private inline fun forEachSubset(candidates: List<ChangeCandidate>, consume: (List<ChangeCandidate>) -> Unit) {
        val total = 1 shl candidates.size
        for (mask in 0 until total) {
            val subset = ArrayList<ChangeCandidate>(candidates.size)
            for (index in candidates.indices) {
                if (mask and (1 shl index) != 0) {
                    subset.add(candidates[index])
                }
            }
            consume(subset)
        }
    }

    /**
     * 同一个 comboId 下增量加入不同 coreMutexOwnGroupIds 时，说明多个互斥核心组被同时保留。
     *
     * 这里保持和 FindBestCombination 相同的增量语义：先检查当前已保留状态，再合并当前卡状态。
     */
    private fun hasCoreMutexConflict(candidates: List<ChangeCandidate>): Boolean {
        val ownGroupIdsByComboId = hashMapOf<String, MutableSet<String>>()

        for (candidate in candidates) {
            for (entry in candidate.comboEntries) {
                if (entry.coreMutexOwnGroupIds.isEmpty()) continue

                val ownGroupIds = ownGroupIdsByComboId.getOrPut(entry.comboId) { linkedSetOf() }
                for (ownGroupId in entry.coreMutexOwnGroupIds) {
                    if (ownGroupIds.any { it != ownGroupId }) return true
                }
                ownGroupIds.addAll(entry.coreMutexOwnGroupIds)
            }
        }

        return false
    }

    private fun List<ChangeCandidate>.isBetterThan(other: List<ChangeCandidate>): Boolean {
        val score = sumOf { it.changeWeight }
        val otherScore = other.sumOf { it.changeWeight }
        if (score != otherScore) return score > otherScore

        val cost = sumOf { it.cost }
        val otherCost = other.sumOf { it.cost }
        if (cost != otherCost) return cost < otherCost

        val weightCompare = compareSortedChangeWeights(other)
        if (weightCompare != 0) return weightCompare > 0

        if (size != other.size) return size < other.size

        return stableKey() < other.stableKey()
    }

    /**
     * 分数相同且费用相同时，优先保留更高单卡 changeWeight 的集合。
     */
    private fun List<ChangeCandidate>.compareSortedChangeWeights(other: List<ChangeCandidate>): Int {
        val weights = map { it.changeWeight }.sortedDescending()
        val otherWeights = other.map { it.changeWeight }.sortedDescending()
        val size = minOf(weights.size, otherWeights.size)

        for (index in 0 until size) {
            val weight = weights[index]
            val otherWeight = otherWeights[index]
            if (weight != otherWeight) return weight.compareTo(otherWeight)
        }

        return 0
    }

    private fun List<ChangeCandidate>.stableKey(): String {
        return map { "${it.source.card.entityId}:${it.source.cardId()}" }
            .sorted()
            .joinToString("|")
    }

    private data class ChangeCandidate(
        val source: ComboCard,
        val cost: Int,
        val changeWeight: Double,
        val comboEntries: List<CardComboEntry>
    )
}
