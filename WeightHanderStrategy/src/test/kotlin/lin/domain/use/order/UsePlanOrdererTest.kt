package lin.domain.use.order

import condition.createMockCard
import lin.bean.CardCombinedConfig
import lin.bean.CardWeightInfo
import lin.bean.ComboCard
import lin.bean.usePlan.MustUseGroupBefore
import lin.bean.usePlan.UseConstraint
import lin.bean.usePlan.UseIntent
import lin.bean.usePlan.UseStage
import lin.domain.use.plan.UsePlan
import lin.domain.use.plan.UsePlanOrderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsePlanOrdererTest {

    @Test
    fun testStableSortNoConstraints() {
        val baseOrdered = listOf("Prep", "Minion", "Eviscerate")
        val result = UsePlanOrderer.stableSortWithConstraints(
            baseOrdered = baseOrdered,
            beforeConstraints = emptyList(),
            togetherConstraints = emptyList()
        )
        assertEquals(baseOrdered, result)
    }

    @Test
    fun testBeforeConstraints() {
        val baseOrdered = listOf("Minion", "Prep", "Eviscerate")
        val result = UsePlanOrderer.stableSortWithConstraints(
            baseOrdered = baseOrdered,
            beforeConstraints = listOf("Prep" to "Eviscerate"),
            togetherConstraints = emptyList()
        )
        assertEquals(listOf("Minion", "Prep", "Eviscerate"), result)

        val baseOrdered2 = listOf("Eviscerate", "Prep")
        val result2 = UsePlanOrderer.stableSortWithConstraints(
            baseOrdered = baseOrdered2,
            beforeConstraints = listOf("Prep" to "Eviscerate")
        )
        assertEquals(listOf("Prep", "Eviscerate"), result2)
    }

    @Test
    fun testTogetherConstraintsAdjacency() {
        val baseOrdered = listOf("Minion", "Prep", "Spell")
        val result = UsePlanOrderer.stableSortWithConstraints(
            baseOrdered = baseOrdered,
            beforeConstraints = emptyList(),
            togetherConstraints = listOf("Prep" to "Spell")
        )
        assertEquals(listOf("Minion", "Prep", "Spell"), result)

        // 压力测试场景：虽然 Minion 在 baseOrdered 中排在 Spell 之前，
        // 但由于 Prep 与 Spell 强邻接，弹出 Prep 时必须立刻带出 Spell，Minion 靠后！
        val baseOrdered2 = listOf("Prep", "Minion", "Spell")
        val result2 = UsePlanOrderer.stableSortWithConstraints(
            baseOrdered = baseOrdered2,
            beforeConstraints = emptyList(),
            togetherConstraints = listOf("Prep" to "Spell")
        )
        assertEquals(listOf("Prep", "Spell", "Minion"), result2)
    }

    @Test
    fun testCyclicDependencyFallback() {
        val baseOrdered = listOf("A", "B")
        val result = UsePlanOrderer.stableSortWithConstraints(
            baseOrdered = baseOrdered,
            beforeConstraints = listOf("A" to "B", "B" to "A")
        )
        assertNull(result)
    }

    @Test
    fun testTogetherCyclicOrConflicting() {
        val baseOrdered = listOf("A", "B")
        val result = UsePlanOrderer.stableSortWithConstraints(
            baseOrdered = baseOrdered,
            beforeConstraints = emptyList(),
            togetherConstraints = listOf("A" to "B", "B" to "A")
        )
        assertNull(result)
    }

    // ==================== order() 端到端：锁定「基础序三键 + 约束边」的实际语义 ====================
    // 上面的用例只覆盖 stableSortWithConstraints 纯函数，不覆盖 baseComparator 与 resolveBeforePairs。

    @Test
    fun `order 按 stage 排序且与输入顺序无关`() {
        val cards = listOf(
            card("end", stage = UseStage.LAST),
            card("resource", stage = UseStage.FIRST),
            card("general", stage = UseStage.GENERAL),
            card("setup", stage = UseStage.SETUP)
        )
        assertEquals(listOf("resource", "setup", "general", "end"), ids(UsePlanOrderer.order(plan(cards))))
    }

    @Test
    fun `order 同段按 orderWeight 降序`() {
        val cards = listOf(
            card("low", stage = UseStage.MID, orderWeight = 0.0),
            card("high", stage = UseStage.MID, orderWeight = 1.0)
        )
        assertEquals(listOf("high", "low"), ids(UsePlanOrderer.order(plan(cards))))
    }

    /**
     * 锁定兜底键现状：orderWeight 相同时按 powerWeight **降序**，即高价值（贵）的牌先出。
     *
     * 这是同段内顺序的**实际主导键**——现有 7 条标签规则的 defaultOrderWeight 全为 0.0，
     * 分组 override 大多也不配，因此落到第三键。方向目前未经论证（直觉上「先小牌试探」常更优）。
     * 若后续调整兜底键方向，本用例即必须同步更新的回归网。
     */
    @Test
    fun `order orderWeight 相同时按 powerWeight 降序`() {
        val cards = listOf(
            card("cheap", stage = UseStage.GENERAL, baseValue = 2.0),
            card("pricey", stage = UseStage.GENERAL, baseValue = 8.0)
        )
        assertEquals(listOf("pricey", "cheap"), ids(UsePlanOrderer.order(plan(cards))))
    }

    @Test
    fun `order 无 UseIntent 的卡回落 GENERAL`() {
        // 无 combinedConfig → useIntent() 为 null → 不进 intents map → 比较器按 GENERAL 兜底
        val cards = listOf(
            card("end", stage = UseStage.LAST),
            ComboCard(card = createMockCard(cardId = "noConfig")),
            card("resource", stage = UseStage.FIRST)
        )
        assertEquals(listOf("resource", "noConfig", "end"), ids(UsePlanOrderer.order(plan(cards))))
    }

    /**
     * relation 跨阶段生效：拓扑排序跑在基础序之上，能把后阶段的牌拉到前阶段牌之前。
     * 与「orderWeight 只管同段」的直觉相反——跨阶段拉动是 relation 的既有能力，不是缺陷，
     * 但意味着阶段并非硬边界，配置时不能假设「END 一定在最后」。
     */
    @Test
    fun `order 约束边可跨阶段拉动顺序`() {
        val cards = listOf(
            card("charge", stage = UseStage.LAST, groups = setOf("dep")),
            card("buff", stage = UseStage.SETUP, groups = setOf("core"))
        )
        val result = UsePlanOrderer.order(plan(cards, MustUseGroupBefore(setOf("core"), setOf("dep"), "combo:test")))
        assertEquals(listOf("buff", "charge"), ids(result))
    }

    /**
     * 边界：同时属于约束两侧的卡会被 resolveBeforePairs 从**两边都排除**。
     * 它仍能通过 ComboUseConstraintBuilder 的存在性过滤（被算作「组内已有卡」），
     * 于是形成「约束已生成、但边数为 0」的静默失效——不报错、不回退，只是没生效。
     */
    @Test
    fun `order 同属约束两侧的卡被排除且约束静默失效`() {
        val cards = listOf(
            card("both", stage = UseStage.SETUP, groups = setOf("core", "dep")),
            card("depOnly", stage = UseStage.LAST, groups = setOf("dep"))
        )
        val result = UsePlanOrderer.order(plan(cards, MustUseGroupBefore(setOf("core"), setOf("dep"), "combo:test")))
        // both 既不算 before 也不算 after → 无边生成 → 退回纯基础序
        assertEquals(listOf("both", "depOnly"), ids(result))
    }

    // ==================== Q-032：阶段排序值配置化 ====================
    // parseStageOrder 是纯函数，可测多组配置；stageOrderValues 有 lazy 缓存，不适合在单测内切换。

    @Test
    fun `空配置回落枚举ordinal`() {
        // 默认等价性：未配置时行为与改造前完全一致（零回归保证）
        val parsed = UsePlanOrderer.parseStageOrder("")
        UseStage.entries.forEach { assertEquals(it.ordinal, parsed[it]) }
    }

    @Test
    fun `配置可改变阶段先后顺序`() {
        // 控制卡组：保命(LATE) 先于 解场(MID)，打破 ordinal 全序（MID=2 < LATE=3）
        val parsed = UsePlanOrderer.parseStageOrder("FIRST:0,LATE:1,SETUP:2,MID:3,GENERAL:4,LAST:5")
        assertEquals(1, parsed[UseStage.LATE])
        assertEquals(3, parsed[UseStage.MID])
        assert(parsed[UseStage.LATE]!! < parsed[UseStage.MID]!!) { "保命应先于解场" }
    }

    @Test
    fun `未列出的阶段回落ordinal`() {
        // 只配 LATE：其余阶段保持原序，配置不完整时行为仍一致
        val parsed = UsePlanOrderer.parseStageOrder("LATE:1")
        assertEquals(1, parsed[UseStage.LATE])
        assertEquals(UseStage.MID.ordinal, parsed[UseStage.MID])
        assertEquals(UseStage.LAST.ordinal, parsed[UseStage.LAST])
    }

    @Test
    fun `非法条目被忽略且不影响其余`() {
        // 未知阶段名 / 非整数值 / 缺冒号 → 忽略；SETUP 仍生效。
        // 注意：旧阶段名（DEFEND/CLEAR 等）已随 Q-032 改名变为非法名，同样走「忽略」分支。
        val parsed = UsePlanOrderer.parseStageOrder("NOT_A_STAGE:1, MID:xx, LATE, SETUP:2")
        assertEquals(UseStage.MID.ordinal, parsed[UseStage.MID])
        assertEquals(UseStage.LATE.ordinal, parsed[UseStage.LATE])
        assertEquals(2, parsed[UseStage.SETUP])
    }

    @Test
    fun `支持负值与并列`() {
        // 负值可插到最前；并列则退化为段内权重竞争
        val parsed = UsePlanOrderer.parseStageOrder("LAST:-1,MID:0,LATE:0")
        assertEquals(-1, parsed[UseStage.LAST])
        assertEquals(parsed[UseStage.MID], parsed[UseStage.LATE])
    }

    private fun card(
        id: String,
        stage: UseStage = UseStage.GENERAL,
        orderWeight: Double = 0.0,
        baseValue: Double = 0.0,
        groups: Set<String> = emptySet()
    ): ComboCard = ComboCard(
        card = createMockCard(cardId = id),
        combinedConfig = CardCombinedConfig(
            weightInfo = CardWeightInfo(cardId = id, powerWeight = 0.0),
            groupIds = groups,
            useIntent = UseIntent(stage = stage, orderWeight = orderWeight)
        ),
        baseValue = baseValue
    )

    /** 与 UsePlanBuilder.build 同构：无 UseIntent 的卡不进 intents map。 */
    private fun plan(cards: List<ComboCard>, vararg constraints: UseConstraint): UsePlan = UsePlan(
        cards = cards,
        intents = cards.mapNotNull { c -> c.useIntent()?.let { c to it } }.toMap(),
        useConstraints = constraints.toList()
    )

    private fun ids(cards: List<ComboCard>): List<String> = cards.map { it.cardId() }
}
