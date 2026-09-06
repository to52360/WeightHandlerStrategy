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
 * T-031 配合通道（高费 0 分卡「有配合才留，不然不留」）的边界也锁定在此：
 * 配对成立随对侧留 / 对侧不在不 solo 留 / 配对名额被抢占换掉 / 负分哨兵不进通道 / 总分负不保送。
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

    @Test
    fun `配合通道：高费0分卡配对成立时随对侧一起留`() {
        val combo = ComboPlanDefinition(
            id = "C1",
            coreGroupIds = setOf("G_A"),
            depGroupIds = setOf("G_B"),
            changeScore = 12.0
        )

        // A = 决战位（2 费 +10），B = 棱彩位（7 费 0 分）：B 单独过不了门禁，
        // 凭声明起手协同进配对 → {A,B} = 10 + 0 + 12 = 22 → 一起留（combo 半激活就此打通）
        val a = card("A", changeWeight = 10.0, cost = 2, groups = setOf("G_A"), comboDefs = listOf(combo))
        val b = card("B", changeWeight = 0.0, cost = 7, groups = setOf("G_B"), comboDefs = listOf(combo))

        assertEquals(setOf("A", "B"), keptIds(listOf(a, b)))
    }

    @Test
    fun `配合通道：对侧不在起手时高费0分卡照样换掉（不然不留）`() {
        val combo = ComboPlanDefinition(
            id = "C1",
            coreGroupIds = setOf("G_A"),
            depGroupIds = setOf("G_B"),
            changeScore = 12.0
        )

        // solo 陷阱：只有 7 费 0 分的依赖侧，{B}=0 能过非负兜底——
        // 若不设防会被 solo 留下占起手位，终选过滤必须把它换掉
        val b = card("B", changeWeight = 0.0, cost = 7, groups = setOf("G_B"), comboDefs = listOf(combo))

        assertEquals(emptySet<String>(), keptIds(listOf(b)))
    }

    @Test
    fun `配合通道：配对名额被更高分抢占时照样换掉`() {
        val combo = ComboPlanDefinition(
            id = "C1",
            coreGroupIds = setOf("G_A"),
            depGroupIds = setOf("G_B"),
            changeScore = 12.0
        )

        // 同侧两张：B2 配对分更高（10+6 > 10+0）入选，B1（0 分通道卡）落选 → 换掉
        val a = card("A", changeWeight = 10.0, cost = 2, groups = setOf("G_A"), comboDefs = listOf(combo))
        val b1 = card("B1", changeWeight = 0.0, cost = 7, groups = setOf("G_B"), comboDefs = listOf(combo))
        val b2 = card("B2", changeWeight = 6.0, cost = 7, groups = setOf("G_B"), comboDefs = listOf(combo))

        assertEquals(setOf("A", "B2"), keptIds(listOf(a, b1, b2)))
    }

    @Test
    fun `负分哨兵：高费负分卡不进配合通道`() {
        val combo = ComboPlanDefinition(
            id = "C1",
            coreGroupIds = setOf("G_A"),
            depGroupIds = setOf("G_B"),
            changeScore = 12.0
        )

        // 负数 changeWeight 是预留哨兵语义（D-008）：门禁直接挡死，协同声明也捞不回
        val a = card("A", changeWeight = 10.0, cost = 2, groups = setOf("G_A"), comboDefs = listOf(combo))
        val b = card("B", changeWeight = -3.0, cost = 7, groups = setOf("G_B"), comboDefs = listOf(combo))

        assertEquals(setOf("A"), keptIds(listOf(a, b)))
    }

    @Test
    fun `配对子集总分不过非负兜底时高费0分卡不保送`() {
        val combo = ComboPlanDefinition(
            id = "C1",
            coreGroupIds = setOf("G_A"),
            depGroupIds = setOf("G_B"),
            changeScore = -5.0  // 协同为负（防未来配置）：配对成立也不保送
        )

        // {A,B} = 0+0-5 = -5 被非负兜底否决；{A} 与 {B} 同为 0 分，费用小者胜 → 留 A 换 B
        val a = card("A", changeWeight = 0.0, cost = 2, groups = setOf("G_A"), comboDefs = listOf(combo))
        val b = card("B", changeWeight = 0.0, cost = 7, groups = setOf("G_B"), comboDefs = listOf(combo))

        assertEquals(setOf("A"), keptIds(listOf(a, b)))
    }
}
