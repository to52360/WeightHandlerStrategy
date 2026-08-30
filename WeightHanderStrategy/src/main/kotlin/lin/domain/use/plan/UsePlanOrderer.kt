package lin.domain.use.plan

import lin.bean.ComboCard
import lin.bean.hasAnyGroup
import lin.bean.usePlan.MustUseGroupBefore
import lin.bean.usePlan.UseIntent
import lin.bean.usePlan.UseStage
import lin.config.EngineConfig
import lin.domain.use.plan.UsePlanOrderer.stageOrderValue
import lin.myLog
import java.util.*

object UsePlanOrderer {
    /**
     * 对 UsePlan 中已选中的牌做最终使用排序。
     *
     * 排序规则：
     * 1. 先按 UseStage / orderWeight / powerWeight 得到稳定基础顺序。
     * 2. 再应用 MustUseGroupBefore 这类组级顺序约束。
     * 3. 如果约束成环，回退基础顺序，不阻断执行链路。
     */
    fun order(plan: UsePlan): List<ComboCard> {
        val baseOrdered = plan.cards.sortedWith(baseComparator(plan.intents))
        val beforePairs = resolveBeforePairs(baseOrdered, plan.useConstraints.filterIsInstance<MustUseGroupBefore>())
        val togetherPairs = emptyList<Pair<ComboCard, ComboCard>>()

        if (beforePairs.isEmpty()) {
            logOrderDecision(baseOrdered, plan.intents, beforePairs)
            return baseOrdered
        }

        return stableSortWithConstraints(baseOrdered, beforePairs, togetherPairs)?.also {
            logOrderDecision(it, plan.intents, beforePairs)
        } ?: run {
            myLog.warn { "UsePlan 存在循环使用约束，回退默认阶段排序: $baseOrdered" }
            baseOrdered
        }
    }

    /**
     * 排序决策追溯日志：回答「这张牌为什么排在这个位置」。
     *
     * 逐卡输出基础序三键的**实际取值**（stage / orderWeight / powerWeight，含 map 缺失时的兜底值），
     * 并列出本轮真正生效的约束边。三类排序问题——阶段不对、同段先后不对、约束没生效——都靠这条日志定位。
     *
     * debug 级别：每轮决策调用一次，生产默认不输出；调顺序时把 lin.domain.use.plan 调到 debug 即可。
     */
    private fun logOrderDecision(
        ordered: List<ComboCard>,
        intents: Map<ComboCard, UseIntent>,
        beforePairs: List<Pair<ComboCard, ComboCard>>
    ) {
        myLog.debug {
            buildString {
                appendLine("出牌顺序决策（${ordered.size} 张）:")
                ordered.forEachIndexed { index, card ->
                    val intent = intents[card]
                    appendLine(
                        "  #$index ${card.cardId()}" +
                                " stage=${intent?.stage ?: UseStage.GENERAL}" +
                                " orderWeight=${intent?.orderWeight ?: 0.0}" +
                                " powerWeight=${card.powerWeight}"
                    )
                }
                append("约束边(${beforePairs.size}):")
                if (beforePairs.isEmpty()) append(" (无)")
                else beforePairs.forEach { (before, after) -> append("\n  ${before.cardId()} -> ${after.cardId()}") }
            }
        }
    }

    /**
     * 默认顺序比较器。
     * UseStage 是粗阶段，orderWeight 是同阶段内的人工偏好，powerWeight 是最后兜底。
     *
     * 阶段先后取自 [stageOrderValue]（Q-032 配置化），不再直接用枚举 ordinal——
     * 使「控制卡组 DEFEND < CLEAR」这类跨卡组的波段顺序差异无需改代码即可表达。
     */
    private fun baseComparator(intents: Map<ComboCard, UseIntent>): Comparator<ComboCard> {
        return compareBy<ComboCard> { stageOrderValue(intents[it]?.stage ?: UseStage.GENERAL) }
            .thenByDescending { intents[it]?.orderWeight ?: 0.0 }
            .thenByDescending { it.powerWeight }
    }

    /**
     * 解析阶段排序配置（`阶段:值` 逗号分隔，值越小越先出）。
     *
     * **纯函数**（无 IO / 无全局状态），便于单测多组配置。容错策略：
     * - 未列出的阶段 → 回落其枚举 ordinal（配置不完整时仍有一致行为，而非崩或全挤一起）
     * - 非法条目（阶段名不存在 / 值非整数 / 缺冒号）→ 忽略并告警，不影响其余条目
     */
    internal fun parseStageOrder(raw: String): Map<UseStage, Int> {
        val parsed = mutableMapOf<UseStage, Int>()
        raw.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { entry ->
                val parts = entry.split(':', limit = 2)
                val stage = parts.getOrNull(0)?.trim()?.let { runCatching { UseStage.valueOf(it) }.getOrNull() }
                val order = parts.getOrNull(1)?.trim()?.toIntOrNull()
                if (stage != null && order != null) parsed[stage] = order
                else myLog.warn { "stage.order 配置项非法，已忽略: $entry" }
            }
        return UseStage.entries.associateWith { parsed[it] ?: it.ordinal }
    }

    /** 进程级缓存：配置是静态量，无需每轮决策重解析。 */
    private val stageOrderValues: Map<UseStage, Int> by lazy { parseStageOrder(EngineConfig.stageOrder) }

    private fun stageOrderValue(stage: UseStage): Int = stageOrderValues[stage] ?: stage.ordinal

    /**
     * 将“组 A 必须先于组 B”解析成本轮已选卡牌之间的拓扑排序边。
     *
     * 这里不能用 Comparator 表达，因为 combo 约束通常只是局部顺序：
     * 例如 A、B 都要先于 C，但 A 和 B 之间没有大小关系，应继续保持基础排序。
     *
     * 返回的 Pair 语义固定为：
     * - first：必须更早使用的卡牌
     * - second：必须更晚使用的卡牌
     *
     * 后续 stableSortWithConstraints 会把这些 Pair 当作有向边 first -> second，
     * 在保留 baseOrdered 稳定顺序的前提下做拓扑排序，并负责检测循环约束。
     */
    private fun resolveBeforePairs(
        cards: List<ComboCard>,
        constraints: List<MustUseGroupBefore>
    ): List<Pair<ComboCard, ComboCard>> {
        return constraints.flatMap { constraint ->
            val beforeCards = cards.filter {
                it.hasAnyGroup(constraint.beforeGroupIds) && !it.hasAnyGroup(constraint.afterGroupIds)
            }
            val afterCards = cards.filter {
                it.hasAnyGroup(constraint.afterGroupIds) && !it.hasAnyGroup(constraint.beforeGroupIds)
            }

            beforeCards.flatMap { before ->
                afterCards.mapNotNull { after ->
                    if (before == after) null else before to after
                }
            }
        }
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
