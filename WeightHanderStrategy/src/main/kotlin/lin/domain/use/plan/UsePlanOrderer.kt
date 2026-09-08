package lin.domain.use.plan

import lin.bean.ComboCard
import lin.bean.hasAnyGroup
import lin.bean.usePlan.MustUseGroupBefore
import lin.bean.usePlan.UseIntent
import lin.bean.usePlan.UseStage
import lin.config.EngineConfig
import lin.domain.use.plan.UsePlanOrderer.fallbackComparator
import lin.domain.use.plan.UsePlanOrderer.stageOrderValue
import lin.myLog
import lin.utils.DecisionLog
import java.util.*

object UsePlanOrderer {
    /**
     * 对 UsePlan 中已选中的牌做最终使用排序。
     *
     * 排序规则：
     * 1. 先按 UseStage / orderWeight / 兜底键（[fallbackComparator]）得到稳定基础顺序。
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
     * 逐卡输出基础序三键的**实际取值**（stage / orderWeight / 兜底键，含 map 缺失时的兜底值），
     * 并列出本轮真正生效的约束边。三类排序问题——阶段不对、同段先后不对、约束没生效——都靠这条日志定位。
     *
     * T-PV-005：日志由 [lin.utils.DecisionLog] 统一开关控制（`decision.log.enabled`，默认关）——
     * 不再依赖宿主 logback 的 debug 级（引擎 jar 不带 logback.xml，部署侧 debug 可能输出不出来）。
     */
    private fun logOrderDecision(
        ordered: List<ComboCard>,
        intents: Map<ComboCard, UseIntent>,
        beforePairs: List<Pair<ComboCard, ComboCard>>
    ) {
        DecisionLog.log {
            buildString {
                appendLine("出牌顺序决策（${ordered.size} 张）: 兜底键=$fallbackKey/$fallbackDirection")
                ordered.forEachIndexed { index, card ->
                    val intent = intents[card]
                    appendLine(
                        "  #$index ${card.cardId()}" +
                                " stage=${intent?.stage ?: UseStage.GENERAL}" +
                                " orderWeight=${intent?.orderWeight ?: 0.0}" +
                                " baseValue=${card.baseValue}" +
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
     * UseStage 是粗阶段，orderWeight 是同阶段内的人工偏好，[fallbackComparator] 是最后兜底。
     *
     * 阶段先后取自 [stageOrderValue]（Q-032 配置化），不再直接用枚举 ordinal——
     * 使「控制卡组 DEFEND < CLEAR」这类跨卡组的波段顺序差异无需改代码即可表达。
     */
    private fun baseComparator(intents: Map<ComboCard, UseIntent>): Comparator<ComboCard> {
        return compareBy<ComboCard> { stageOrderValue(intents[it]?.stage ?: UseStage.GENERAL) }
            .thenByDescending { intents[it]?.orderWeight ?: 0.0 }
            .then(fallbackComparator())
    }

    // ==================== 兜底键（T-037）====================
    // stage 与 orderWeight 都分不出先后时的最后 tie-break。它是同段内顺序的**实际主导键**
    // （7 条标签规则里 6 条 defaultOrderWeight=0，分组 override 大多不配），所以它的量纲必须干净、方向必须可配。
    //
    // 旧行为硬编码 `powerWeight` 降序。powerWeight = baseValue + 树分 + 光环分 + legacy handler 分 + BaseWeight，
    // 是**选牌层**的混合评分。逐分量问「它对『谁先出』有贡献吗」：
    //   - 树分 = 局面战术信号 → **有贡献**，且是唯一想要的（战术分高的先出，免费复用评估树已有的信息优先）；
    //   - baseValue = 物理价值 → 有价值锚/稳定性，且与局面无关，用作并列时的 tie-break；
    //   - 光环分 → 全局加成，与自身先后无关，**无贡献**；
    //   - legacy handler 分 → 选牌信号量纲未知（可能几十分），**会压倒前两项**，引入不可解释抖动；
    //   - BaseWeight(+1) → 所有牌相同，对相对序**零影响**。
    // 结论：只取树分 + baseValue，排除光环/legacy/常数噪声。
    //
    // 载体三档（chain 形式，direction 作用于整条链）：
    // - key = tactical（默认）：tacticalScore 降序 → baseValue 降序。战术层自动涌现，降低逐卡编排要求；
    //   ts 相同/为 0（未配评估树）退回 baseValue，既解决大面积并列又保留稳定价值锚。
    // - key = base：只看 baseValue，局面战术信号完全不参与排序（最可复现）。
    // - key = weight：powerWeight，旧行为，仅作 A/B 回退通道（一标多义，不推荐长期使用）。
    //
    // 为什么是**分层**（ts 序数优先）而不是**合成**（surplusFillValue 的 E + ts）：
    // 分层只用树分的**序数**（谁更命中），合成要用树分的**基数**（具体值）。Q-024 量纲费化后 ts
    // 已是费、基数与 E 同轴可比，是否将兜底键改为合成留作后续评估（属行为改动，不随 T-PV-011 批次）。
    //
    // @verify use-intent-model/K-001: 方向本身仍未实战校准。desc 与 asc 各有论证（desc=高价值先落袋防中断；
    // asc=逐张 replan 架构下先出低费保留选择面），未做对局对比，故保留现状 desc 并留配置开关。
    // @verify use-intent-model/K-002: 战术层（key=tactical）默认启用未经实战校准。

    enum class FallbackKey { TACTICAL, BASE, WEIGHT }

    enum class FallbackDirection { DESC, ASC }

    /** 解析兜底键载体配置（纯函数）：`tactical`（默认）/ `base` / `weight`，非法值回落 tactical 并告警。 */
    internal fun parseFallbackKey(raw: String?): FallbackKey = when (raw?.trim()?.lowercase()) {
        "weight", "powerweight" -> FallbackKey.WEIGHT
        "base", "basevalue" -> FallbackKey.BASE
        "tactical", "", null -> FallbackKey.TACTICAL
        else -> {
            myLog.warn { "order.fallback.key 配置值非法（可选 tactical/base/weight），已回落 tactical: $raw" }
            FallbackKey.TACTICAL
        }
    }

    /** 解析兜底键方向配置（纯函数）：`desc`（默认，价值高者先出）/ `asc`，非法值回落 desc 并告警。 */
    internal fun parseFallbackDirection(raw: String?): FallbackDirection = when (raw?.trim()?.lowercase()) {
        "asc", "ascending" -> FallbackDirection.ASC
        "desc", "descending", "", null -> FallbackDirection.DESC
        else -> {
            myLog.warn { "order.fallback.direction 配置值非法（可选 desc/asc），已回落 desc: $raw" }
            FallbackDirection.DESC
        }
    }

    /**
     * 按 (载体, 方向) 造比较器（纯函数），便于单测覆盖各档与两种方向而不依赖进程级配置。
     *
     * 载体是一条**有序链**：前一个键分出胜负就不再看后面；[FallbackDirection] 作用于整条链（同向）。
     */
    internal fun fallbackComparator(key: FallbackKey, direction: FallbackDirection): Comparator<ComboCard> {
        val chain: List<(ComboCard) -> Double> = when (key) {
            FallbackKey.TACTICAL -> listOf({ it.tacticalScore }, { it.baseValue })
            FallbackKey.BASE -> listOf({ it.baseValue })
            FallbackKey.WEIGHT -> listOf({ it.powerWeight })
        }
        val desc = direction == FallbackDirection.DESC
        var comparator = chain.first().let { first ->
            if (desc) compareByDescending(first) else compareBy(first)
        }
        for (selector in chain.drop(1)) {
            comparator = if (desc) comparator.thenByDescending(selector) else comparator.thenBy(selector)
        }
        return comparator
    }

    /** 进程级缓存：配置是静态量，无需每轮决策重解析。 */
    private val fallbackKey: FallbackKey by lazy { parseFallbackKey(EngineConfig.orderFallbackKey) }
    private val fallbackDirection: FallbackDirection by lazy { parseFallbackDirection(EngineConfig.orderFallbackDirection) }

    private fun fallbackComparator(): Comparator<ComboCard> = fallbackComparator(fallbackKey, fallbackDirection)

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
