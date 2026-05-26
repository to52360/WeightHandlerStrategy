package lin.domain.use.order

import lin.bean.Combo
import lin.bean.ComboCard
import lin.domain.combo.ComboParse
import lin.domain.context.NotWeight
import lin.myLog
import java.util.*

object UseOrderPlanner {
    val BASE_ORDER: Comparator<ComboCard> =
        compareBy<ComboCard> { it.useGroupId }
            .thenByDescending { it.useGroupOrder }
            .thenByDescending { it.powerWeight }

    fun sort(cards: List<ComboCard>): List<ComboCard> {
        if (cards.size < 2) return cards

        val baseOrdered = cards.sortedWith(BASE_ORDER)
        val edges = buildBeforeEdges(baseOrdered)
        if (edges.isEmpty()) return baseOrdered

        return sortByEdges(baseOrdered, edges) ?: run {
            myLog.warn { "出牌顺序约束存在循环，回退基础排序: $baseOrdered" }
            baseOrdered
        }
    }

    private fun buildBeforeEdges(cards: List<ComboCard>): Map<ComboCard, Set<ComboCard>> {
        val edges = linkedMapOf<ComboCard, MutableSet<ComboCard>>()
        for (beforeCard in cards) {
            beforeCard.combo
                ?.filter { it.comboType == ComboParse.BEFORE }
                ?.forEach { combo ->
                    cards.forEach { afterCard ->
                        if (beforeCard != afterCard && combo.matches(afterCard)) {
                            edges.getOrPut(beforeCard) { linkedSetOf() }.add(afterCard)
                        }
                    }
                }
        }
        return edges
    }

    private fun Combo.matches(card: ComboCard): Boolean = comboRule(card) != NotWeight

    private fun sortByEdges(
        baseOrdered: List<ComboCard>,
        edges: Map<ComboCard, Set<ComboCard>>
    ): List<ComboCard>? {
        val baseIndex = baseOrdered.withIndex().associate { it.value to it.index }
        val outgoing = baseOrdered.associateWith { linkedSetOf<ComboCard>() }.toMutableMap()
        val indegree = baseOrdered.associateWith { 0 }.toMutableMap()

        edges.forEach { (from, targets) ->
            targets.forEach { to ->
                if (outgoing.getValue(from).add(to)) {
                    indegree[to] = indegree.getValue(to) + 1
                }
            }
        }

        val ready = PriorityQueue<ComboCard>(compareBy { baseIndex.getValue(it) })
        baseOrdered.forEach { card ->
            if (indegree.getValue(card) == 0) ready.add(card)
        }

        val sorted = mutableListOf<ComboCard>()
        while (ready.isNotEmpty()) {
            val current = ready.remove()
            sorted.add(current)
            outgoing.getValue(current).forEach { next ->
                val nextIndegree = indegree.getValue(next) - 1
                indegree[next] = nextIndegree
                if (nextIndegree == 0) ready.add(next)
            }
        }

        return if (sorted.size == baseOrdered.size) sorted else null
    }
}
