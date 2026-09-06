package lin.domain.result

import lin.bean.ComboCard
import lin.bean.cardExt.base.changeWeight
import lin.bean.usePlan.CardComboEntry
import lin.config.EngineConfig
import lin.domain.context.NotWeight

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
 * 保留子集的评分 = Σ`changeWeight`（单卡留牌价值，独立通道）
 * **+ Σ`changeScore`（combo 起手协同加分，只有被选中的配对才计，同一 combo 只计一次）**。
 *
 * ## 选一组（M2）：`changeScore != 0` 的 combo 会在起手**配对**
 *
 * 每个声明了 `changeScore` 的 combo，在起手候选里选出**唯一一组**：核心侧取 1 张 + 依赖侧取 1 张
 * （按两张卡 `changeWeight` 之和取最高）。该组的 `changeScore` 才计入评分；
 * **同一侧未被选中的卡一律换掉**——这是「核心与依赖各留一张，留太多会卡手」的落点。
 *
 * `changeScore == 0`（未声明起手协同）的 combo **完全不干预起手**：不配对、不剔除，
 * 行为与加该字段前逐字一致——向后兼容的底线。
 *
 * ## 配合通道（T-031）：高费 0 分卡「有配合才留，不然不留」
 *
 * 高费（cost > keepCost）0 分卡过不了通用门禁；若声明了起手协同（存在 `changeScore != 0` 的 combo），
 * 允许作为**配对候选**进入选择：配对成立且配对子集过非负兜底，才随对侧一起留；
 * 对侧不在（未凑齐 / 配对名额被更高分抢占）→ 一律换掉。负分高费卡仍被门禁挡死——
 * 负数 `changeWeight` 是预留哨兵语义（D-008），配合通道不占用。
 *
 * 语义来源：初版 `ChangeWeightResult`（b9160b74）「规则卡 + 唯一最佳配合卡，其余配合卡全换掉」；
 * f6b2df0 迁移到 combo 体系时这条语义漏搬，此处用 combo 结构重新表达（不复活旧的 `ComboRule`）。
 *
 * 并复用 CardComboEntry 的 coreMutex 关系做硬约束。不写日志、不改外部集合、不依赖 Koin，便于独立验证。
 *
 * ⚠️ **出牌侧的 `CardComboEntry.score` 从不参与起手决策**——起手与出牌是两条独立轴。
 */
object ChangeCardSelector {

    fun select(cards: List<ComboCard>, keepCost: Int = EngineConfig.changeKeepCost): ChangeDecision {
        if (cards.isEmpty()) {
            return ChangeDecision(emptySet(), emptySet())
        }

        val candidates = cards.map { it.toChangeCandidate(keepCost) }
            .filter { it.isKeepCandidate(keepCost) }
        val bestKeepCards = findBestKeepSet(candidates)
        val keepSet = bestKeepCards.map { it.source }.toSet()

        return ChangeDecision(
            keepCards = keepSet,
            removeCards = cards.filterNot { it in keepSet }.toSet()
        )
    }

    private fun ComboCard.toChangeCandidate(keepCost: Int): ChangeCandidate {
        val cardCost = cost()
        val weight = changeWeight()
        return ChangeCandidate(
            source = this,
            cost = cardCost,
            changeWeight = weight,
            groupIds = allGroupIds,
            comboEntries = comboEntries,
            // 零分卡才走配合通道；显式比 0.0，不借 NotWeight（其语义是中性哨兵，负数哨兵重构会动它）
            comboChannelEntrant = cardCost > keepCost && weight == 0.0
        )
    }

    private fun ChangeCandidate.isKeepCandidate(keepCost: Int): Boolean {
        return if (cost <= keepCost) {
            changeWeight >= NotWeight
        } else {
            // 高费 0 分卡的「配合通道」：声明了起手协同才有资格进配对；
            // 对侧不在时由终选过滤剔除（isUnpairedComboChannel），不会 solo 留下
            changeWeight > NotWeight || (comboChannelEntrant && comboEntries.any { it.changeScore != 0.0 })
        }
    }

    private fun findBestKeepSet(candidates: List<ChangeCandidate>): List<ChangeCandidate> {
        if (candidates.isEmpty()) return emptyList()

        val matches = resolveKeepCombos(candidates)
        // M2：配对成功的 combo，同侧未选中的卡一律换掉——「核心与依赖各留一张，留多卡手」
        val survivors = dropUnmatchedSides(candidates, matches)

        var best: List<ChangeCandidate>? = null

        forEachSubset(survivors) { subset ->
            val currentBest = best
            if (subset.isNotEmpty() &&
                !hasCoreMutexConflict(subset) &&
                (currentBest == null || subset.isBetterThan(currentBest, matches))
            ) {
                best = subset
            }
        }

        val bestKeepCards = best ?: return emptyList()
        if (bestKeepCards.keepScore(matches) < NotWeight) return emptyList()
        return bestKeepCards.filterNot { it.isUnpairedComboChannel(matches, bestKeepCards) }
    }

    /**
     * 配合通道卡是否该被剔除：找不到一条「自己在内、且 core/dep 两侧都在保留集」的配对即剔除。
     *
     * 0 分卡入候选后 `{卡}=0` 能过非负兜底——若配对不成立仍被留，就成了「无协同也占起手位」，
     * 正是「有配合才留，不然不留」要堵的 solo 陷阱。非配合通道卡恒 false（凭自身分数留，不与配对捆绑）。
     */
    private fun ChangeCandidate.isUnpairedComboChannel(
        matches: List<KeepComboMatch>,
        kept: List<ChangeCandidate>
    ): Boolean {
        if (!comboChannelEntrant) return false
        // 依赖不变量：matches 与 kept 是同一批 ChangeCandidate 对象（candidates→survivors→best 全程 filter/子集复用引用）。
        // 若未来链路重建对象（map/copy），=== 会静默失效导致 solo 卡漏剔除——届时改以 source.cardId() 作稳定键。
        return matches.none { match ->
            (match.core === this || match.dep === this) &&
                    kept.any { it === match.core } &&
                    kept.any { it === match.dep }
        }
    }

    /**
     * 保留子集的评分：单卡留牌价值之和 + 被选中配对（两张都在子集里）的 combo 起手加分。
     *
     * 比较与最终非负判定都走这里，保证「选优」与「要不要留」两处口径一致。
     */
    private fun List<ChangeCandidate>.keepScore(matches: List<KeepComboMatch>): Double =
        sumOf { it.changeWeight } + matches.sumOf { match ->
            if (any { it === match.core } && any { it === match.dep }) match.changeScore else 0.0
        }

    /**
     * M2 配对：为每个**声明了 `changeScore`** 的 combo 选出唯一一组（核心侧 1 张 + 依赖侧 1 张），
     * 按两张卡 `changeWeight` 之和取最高。
     *
     * 缺一侧则不成立（不成组 → 相关卡不受影响，仍走通用单卡规则；
     * 配合通道卡除外——它们本就不过通用门禁，缺一侧即被终选过滤剔除）；
     * `changeScore == 0` 的 combo 不参与——未声明起手协同就不干预起手。
     */
    private fun resolveKeepCombos(candidates: List<ChangeCandidate>): List<KeepComboMatch> {
        val coreSide = linkedMapOf<String, MutableList<ChangeCandidate>>()
        val depSide = linkedMapOf<String, MutableList<ChangeCandidate>>()
        val changeScoreByCombo = linkedMapOf<String, Double>()

        for (candidate in candidates) {
            for (entry in candidate.comboEntries) {
                if (entry.changeScore == 0.0) continue
                changeScoreByCombo[entry.comboId] = entry.changeScore
                val side = if (candidate.groupIds.any { it in entry.coreGroupIds }) coreSide else depSide
                side.getOrPut(entry.comboId) { mutableListOf() }.add(candidate)
            }
        }

        return changeScoreByCombo.keys.mapNotNull { comboId ->
            val cores = coreSide[comboId].orEmpty()
            val deps = depSide[comboId].orEmpty()
            if (cores.isEmpty() || deps.isEmpty()) return@mapNotNull null

            var best: KeepComboMatch? = null
            for (core in cores) {
                for (dep in deps) {
                    val match = KeepComboMatch(comboId, core, dep, changeScoreByCombo[comboId] ?: 0.0)
                    if (best == null || match.pairWeight() > best.pairWeight()) best = match
                }
            }
            best
        }
    }

    /**
     * 剔除落选的同侧卡：参与了**已配对成功** combo、却没被选中的卡 → 换掉（不论单卡分多高）。
     *
     * 这是「只留一组」的执行点。参与的是未配对成功的 combo（缺一侧）的卡不受影响——
     * 那种情况是「没凑齐」，不是「留多了」。
     */
    private fun dropUnmatchedSides(
        candidates: List<ChangeCandidate>,
        matches: List<KeepComboMatch>
    ): List<ChangeCandidate> {
        if (matches.isEmpty()) return candidates

        val matchedComboIds = matches.map { it.comboId }.toSet()
        return candidates.filter { candidate ->
            val chosen = matches.any { it.core === candidate || it.dep === candidate }
            val inMatchedCombo = candidate.comboEntries.any { it.comboId in matchedComboIds }
            chosen || !inMatchedCombo
        }
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

    private fun List<ChangeCandidate>.isBetterThan(
        other: List<ChangeCandidate>,
        matches: List<KeepComboMatch>
    ): Boolean {
        val score = keepScore(matches)
        val otherScore = other.keepScore(matches)
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

    /**
     * 起手侧一个 combo 的命中配对：核心侧 1 张 + 依赖侧 1 张。
     *
     * @param changeScore 该 combo 的起手协同加分，仅当两张都在保留子集里时才计入。
     */
    private data class KeepComboMatch(
        val comboId: String,
        val core: ChangeCandidate,
        val dep: ChangeCandidate,
        val changeScore: Double
    ) {
        /** 配对选优依据：两张卡的单卡留牌价值之和（不含 changeScore——它对所有配对是同一个值） */
        fun pairWeight(): Double = core.changeWeight + dep.changeWeight
    }

    private data class ChangeCandidate(
        val source: ComboCard,
        val cost: Int,
        val changeWeight: Double,
        /** 该卡所属分组（静态 ∪ 谓词），用于判定 combo 的 counterpart 是否被同时保留 */
        val groupIds: Set<String>,
        val comboEntries: List<CardComboEntry>,
        /** 高费 0 分、仅凭「配合通道」入候选的卡：配对另一侧不在保留集就必须换掉（T-031） */
        val comboChannelEntrant: Boolean
    )
}
