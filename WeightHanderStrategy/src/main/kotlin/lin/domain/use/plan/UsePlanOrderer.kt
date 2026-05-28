package lin.domain.use.plan

import lin.bean.ComboCard
import lin.bean.usePlan.MustUseBefore
import lin.bean.usePlan.MustUseTogether
import lin.bean.usePlan.UseIntent
import lin.bean.usePlan.UseStage
import lin.myLog
import java.util.*

object UsePlanOrderer {
    /**
     * 对 UsePlan 中已选中的牌做最终使用排序。
     *
     * 排序规则：
     * 1. 先按 UseStage / orderWeight / powerWeight 得到稳定基础顺序。
     * 2. 再应用 MustUseBefore 和 MustUseTogether 这类顺序约束。
     * 3. 如果约束成环，回退基础顺序，不阻断执行链路。
     */
    fun order(plan: UsePlan): List<ComboCard> {
        val baseOrdered = plan.cards.sortedWith(baseComparator(plan.intents))
        val beforePairs = plan.useConstraints.filterIsInstance<MustUseBefore>().map { it.before to it.after }
        val togetherPairs = plan.useConstraints.filterIsInstance<MustUseTogether>().map { it.first to it.second }

        if (beforePairs.isEmpty() && togetherPairs.isEmpty()) return baseOrdered

        return stableSortWithConstraints(baseOrdered, beforePairs, togetherPairs) ?: run {
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
     * 🌟 纯函数拓扑/强邻接排序引擎。
     * 无任何域对象依赖，极其方便单独测试！
     */
    fun <T> stableSortWithConstraints(
        baseOrdered: List<T>,
        beforeConstraints: List<Pair<T, T>>,
        togetherConstraints: List<Pair<T, T>> = emptyList()
    ): List<T>? {
        val cardSet = baseOrdered.toSet()
        val baseIndex = baseOrdered.withIndex().associate { it.value to it.index }

        val togetherMap = togetherConstraints
            .filter { it.first in cardSet && it.second in cardSet && it.first != it.second }
            .associate { it.first to it.second }

        if (togetherMap.size != togetherConstraints.size) return null

        val togetherSeconds = togetherMap.values.toSet()

        val outgoing = baseOrdered.associateWith { linkedSetOf<T>() }.toMutableMap()
        val indegree = baseOrdered.associateWith { 0 }.toMutableMap()

        val allBeforeConstraints = beforeConstraints.toMutableList()
        togetherMap.forEach { (first, second) ->
            allBeforeConstraints.add(first to second)
        }

        allBeforeConstraints
            .filter { it.first in cardSet && it.second in cardSet && it.first != it.second }
            .forEach { (before, after) ->
                if (outgoing.getValue(before).add(after)) {
                    indegree[after] = indegree.getValue(after) + 1
                }
            }

        val ready = PriorityQueue<T>(compareBy { baseIndex.getValue(it) })
        baseOrdered.forEach { card ->
            if (indegree.getValue(card) == 0 && card !in togetherSeconds) {
                ready.add(card)
            }
        }

        val sorted = mutableListOf<T>()
        val visited = mutableSetOf<T>()

        while (ready.isNotEmpty()) {
            var current = ready.remove()

            while (true) {
                if (visited.add(current)) {
                    sorted.add(current)
                }

                outgoing.getValue(current).forEach { next ->
                    val nextIndegree = indegree.getValue(next) - 1
                    indegree[next] = nextIndegree
                    if (nextIndegree == 0 && next !in togetherSeconds) {
                        ready.add(next)
                    }
                }

                val nextTogether = togetherMap[current]
                if (nextTogether != null && nextTogether !in visited) {
                    current = nextTogether
                } else {
                    break
                }
            }
        }

        return if (sorted.size == baseOrdered.size) sorted else null
    }
}
