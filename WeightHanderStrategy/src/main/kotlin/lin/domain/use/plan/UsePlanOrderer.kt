package lin.domain.use.plan

import lin.bean.ComboCard
import lin.myLog
import java.util.*

object UsePlanOrderer {
    /**
     * 对 UsePlan 中已选中的牌做最终使用排序。
     *
     * 排序规则：
     * 1. 先按 UseStage / orderWeight / powerWeight 得到稳定基础顺序。
     * 2. 再应用 MustUseBefore 这类硬顺序约束。
     * 3. 如果约束成环，回退基础顺序，不阻断执行链路。
     */
    fun order(plan: UsePlan): List<ComboCard> {
        val baseOrdered = plan.cards.sortedWith(baseComparator(plan.intents))
        val mustBefore = plan.useConstraints.filterIsInstance<MustUseBefore>()
        if (mustBefore.isEmpty()) return baseOrdered
        return topologicalSort(baseOrdered, mustBefore) ?: run {
            myLog.warn { "UsePlan 存在循环使用约束，回退默认阶段排序: $baseOrdered" }
            baseOrdered
        }
    }

    /**
     * 默认顺序比较器。
     * UseStage 是粗阶段，orderWeight 是同阶段内的人工偏好，powerWeight 是最后兜底。
     */
    private fun baseComparator(intents: Map<ComboCard, UseIntent>): Comparator<ComboCard> {
        return compareBy<ComboCard> { intents[it]?.stage?.ordinal ?: UseStage.VALUE.ordinal }
            .thenByDescending { intents[it]?.orderWeight ?: 0.0 }
            .thenByDescending { it.powerWeight }
    }

    /**
     * 对 MustUseBefore 约束做稳定拓扑排序。
     * 入度为 0 的牌按基础顺序进入队列，保证无关牌不会随机乱跳。
     */
    private fun topologicalSort(
        baseOrdered: List<ComboCard>,
        constraints: List<MustUseBefore>
    ): List<ComboCard>? {
        val cardSet = baseOrdered.toSet()
        val baseIndex = baseOrdered.withIndex().associate { it.value to it.index }
        val outgoing = baseOrdered.associateWith { linkedSetOf<ComboCard>() }.toMutableMap()
        val indegree = baseOrdered.associateWith { 0 }.toMutableMap()

        constraints
            .filter { it.before in cardSet && it.after in cardSet && it.before != it.after }
            .forEach { constraint ->
                if (outgoing.getValue(constraint.before).add(constraint.after)) {
                    indegree[constraint.after] = indegree.getValue(constraint.after) + 1
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
