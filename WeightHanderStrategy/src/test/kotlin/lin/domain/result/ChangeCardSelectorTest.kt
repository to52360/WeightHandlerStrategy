package lin.domain.result

import condition.createMockCard
import lin.bean.CardCombinedConfig
import lin.bean.CardWeightInfo
import lin.bean.ComboCard
import lin.bean.usePlan.ComboPlanDefinition
import lin.domain.use.plan.ComboIndex
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 起手换牌选择：combo 级「起手协同加分」`changeScore`（Q-009）。
 *
 * 缺口背景：起手只按单卡 `changeWeight` 挑保留子集，表达不了
 * 「A、B 单留都一般，一起留才值钱」——单卡通道给不出**组合溢价**
 * （A=0 时与 {B} 同分，比较器下一键是「总费用小者胜」，A 必被挤掉）。
 *
 * 本测试锁定三件事：
 * ① 能力生效：配了 `changeScore` 后组合卡一起留下；
 * ② 默认 0 时行为与加字段前完全一致（向后兼容是这次改动的安全底线）；
 * ③ 同一 combo 在一次子集评分中只计一次（两侧成员各持一条 entry，不去重会双计）。
 *
 * 另可佐证：`combo.score`（出牌协同加分）始终不参与起手决策——用例 ② 里
 * 出牌分给 0、起手分给 10，两者互不干扰。
 */
class ChangeCardSelectorTest {

    /**
     * 造一张起手候选卡。
     *
     * 走**静态组**路径（谓词组为空 → 引擎直读静态预算），故 combo 条目在此手工装配：
     * 用生产同一入口 [ComboIndex] 生成，保证与 `ComboAssembler` 的归进口径一致
     * （含 `changeScore` 透传），测试不复制一份装配逻辑。
     */
    private fun card(
        id: String,
        changeWeight: Double,
        cost: Int = 1,
        groups: Set<String> = emptySet(),
        comboDefs: List<ComboPlanDefinition> = emptyList()
    ): ComboCard = ComboCard(
        card = createMockCard(cardId = id, cost = cost),
        combinedConfig = CardCombinedConfig(
            weightInfo = CardWeightInfo(cardId = id, powerWeight = 0.0, changeWeight = changeWeight),
            groupIds = groups,
            comboEntries = if (comboDefs.isEmpty()) emptyList() else ComboIndex(comboDefs).entries(groups)
        )
    )

    private fun keptIds(cards: List<ComboCard>): Set<String> =
        ChangeCardSelector.select(cards).keepCards.map { it.cardId() }.toSet()

    @Test
    fun `changeScore 默认 0 时只按单卡 changeWeight 选优`() {
        val combo = ComboPlanDefinition(
            id = "C1",
            coreGroupIds = setOf("G_A"),
            depGroupIds = setOf("G_B"),
            score = 0.0
        )

        // A 单卡留牌价值为 0：与 {B} 同分（0+10 == 10），
        // 比较器下一键是「总费用小者胜」→ A 被挤掉。这正是 Q-009 缺口的现场。
        val a = card("A", changeWeight = 0.0, groups = setOf("G_A"), comboDefs = listOf(combo))
        val b = card("B", changeWeight = 10.0, groups = setOf("G_B"), comboDefs = listOf(combo))

        assertEquals(setOf("B"), keptIds(listOf(a, b)))
    }

    @Test
    fun `起手协同加分让组合卡一起留下`() {
        val combo = ComboPlanDefinition(
            id = "C1",
            coreGroupIds = setOf("G_A"),
            depGroupIds = setOf("G_B"),
            score = 0.0,      // 出牌协同加分：不参与起手决策
            changeScore = 10.0 // 起手协同加分：counterpart 齐备才加
        )

        val a = card("A", changeWeight = 0.0, groups = setOf("G_A"), comboDefs = listOf(combo))
        val b = card("B", changeWeight = 10.0, groups = setOf("G_B"), comboDefs = listOf(combo))

        // {A,B} = 0 + 10 + 10 = 20 > {B} = 10 → 两侧都留住
        assertEquals(setOf("A", "B"), keptIds(listOf(a, b)))
    }

    @Test
    fun `同一 combo 的起手加分只计一次`() {
        val keepCombo = ComboPlanDefinition(
            id = "C_KEEP",
            coreGroupIds = setOf("G_A"),
            depGroupIds = setOf("G_B"),
            changeScore = 10.0
        )
        // 只借用它的 coreMutex：A 与 C 同为核心组且互斥，
        // 使 {A,B,C} / {A,C} 被否决，从而让 {A,B} 与 {C} 直接竞争。
        val mutexCombo = ComboPlanDefinition(
            id = "C_MUTEX",
            coreGroupIds = setOf("G_A", "G_C"),
            depGroupIds = setOf("G_NONE"),
            coreMutex = true
        )
        val defs = listOf(keepCombo, mutexCombo)

        val a = card("A", changeWeight = 0.0, groups = setOf("G_A"), comboDefs = defs)
        val b = card("B", changeWeight = 0.0, groups = setOf("G_B"), comboDefs = defs)
        val c = card("C", changeWeight = 15.0, groups = setOf("G_C"), comboDefs = defs)

        // 计一次：{A,B} = 0+0+10 = 10 < {C} = 15 → 留 C；
        // 若两侧 entry 各加一次（20）则会反过来选 {A,B}，该断言即双计防线。
        assertEquals(setOf("C"), keptIds(listOf(a, b, c)))
    }

    @Test
    fun `选一组：核心与依赖各留一张，同侧其余换掉`() {
        val combo = ComboPlanDefinition(
            id = "C1",
            coreGroupIds = setOf("G_A"),
            depGroupIds = setOf("G_B"),
            changeScore = 10.0
        )

        // 核心侧两张：A1 与 B1 配对分更高（10+6 > 8+6）→ A1 入选，A2 落选被换掉
        val a1 = card("A1", changeWeight = 10.0, groups = setOf("G_A"), comboDefs = listOf(combo))
        val a2 = card("A2", changeWeight = 8.0, groups = setOf("G_A"), comboDefs = listOf(combo))
        val b1 = card("B1", changeWeight = 6.0, groups = setOf("G_B"), comboDefs = listOf(combo))

        // 若不做「只留一组」：{A1,A2,B1} = 24 + 10 = 34 会全留（留多卡手的现场）
        assertEquals(setOf("A1", "B1"), keptIds(listOf(a1, a2, b1)))
    }

    @Test
    fun `changeScore 为 0 的 combo 完全不干预起手`() {
        val combo = ComboPlanDefinition(
            id = "C1",
            coreGroupIds = setOf("G_A"),
            depGroupIds = setOf("G_B")
            // changeScore 缺省 0：未声明起手协同 → 不配对、不剔除
        )

        val a1 = card("A1", changeWeight = 10.0, groups = setOf("G_A"), comboDefs = listOf(combo))
        val a2 = card("A2", changeWeight = 8.0, groups = setOf("G_A"), comboDefs = listOf(combo))
        val b1 = card("B1", changeWeight = 6.0, groups = setOf("G_B"), comboDefs = listOf(combo))

        // 与加 changeScore 字段前逐字一致：全留
        assertEquals(setOf("A1", "A2", "B1"), keptIds(listOf(a1, a2, b1)))
    }

    @Test
    fun `缺一侧的 combo 不成组，相关卡按单卡规则保留`() {
        val combo = ComboPlanDefinition(
            id = "C1",
            coreGroupIds = setOf("G_A"),
            depGroupIds = setOf("G_B"),
            changeScore = 10.0
        )

        // 手里只有核心侧，没有依赖侧 → 凑不齐，不该把 A 当成「落选」换掉
        val a = card("A", changeWeight = 10.0, groups = setOf("G_A"), comboDefs = listOf(combo))

        assertEquals(setOf("A"), keptIds(listOf(a)))
    }

    @Test
    fun `单卡留牌价值为负时仍会被换掉（协同加分救不了必须换的牌）`() {
        val combo = ComboPlanDefinition(
            id = "C1",
            coreGroupIds = setOf("G_A"),
            depGroupIds = setOf("G_B"),
            changeScore = 10.0
        )

        // A 起手必换（-100）且是高费：准入门禁在选优之前，changeScore 不会把它捞回来
        val a = card("A", changeWeight = -100.0, cost = 5, groups = setOf("G_A"), comboDefs = listOf(combo))
        val b = card("B", changeWeight = 10.0, groups = setOf("G_B"), comboDefs = listOf(combo))

        assertEquals(setOf("B"), keptIds(listOf(a, b)))
    }
}
