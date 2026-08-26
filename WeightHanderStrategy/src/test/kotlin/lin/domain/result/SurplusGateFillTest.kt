package lin.domain.result

import condition.createMockCard
import lin.bean.*
import lin.bean.usePlan.UseIntent
import org.junit.Assert.*
import org.junit.Test

/**
 * D-007 双费数机会成本模型 v3（T-015）+ D-012 门槛语义 v2：等效费 E（D-014 原语义，不含战术溢价）
 * + 垫后余量门槛 N（放行 ⟺ 空闲 ≥ 牌费 + N，「垫出后空闲池仍须剩 N 费」，跨卡费同义）
 * → fillValue = E + 树分×scale（费单位）。
 *
 * 语义锚点（D-012 拍板例）：① 3 费卡 N=1 = 空闲 4 才垫（垫后仍剩 1 费）；②「不贪心」= 配更小的 N；
 * ③ 未配置 = 0 付得起即垫（D-005「无战术不死捏」）；④ v1 池语义（空闲 ≥ max(牌费,N)，N≤牌费静默空设）已废弃。
 *
 * T-026：CandidatePolicy 枚举退役，惜售语义统一由 N>0（未配置 = 0）表达，不再有 TACTICS_DOMINANT/SURPLUS_ONLY。
 */
class SurplusGateFillTest {

    private fun buildCard(
        cardId: String,
        cost: Int,
        powerWeight: Double,
        idleThreshold: Int? = null,
        groupIdleThreshold: Int? = null,
        tacticalScore: Double = 0.0
    ): ComboCard {
        val info = CardWeightInfo(
            cardId = cardId,
            powerWeight = powerWeight,
            surplusIdleThreshold = idleThreshold
        )
        val card = ComboCard(
            combinedConfig = CardCombinedConfig(
                weightInfo = info,
                useIntent = UseIntent(),
                groupSurplusIdleThreshold = groupIdleThreshold
            ),
            card = createMockCard(cardId = cardId, cost = cost)
        )
        card.extPowerWeight = 1.0
        card.tacticalScore = tacticalScore
        return card
    }

    // ===== 余费门槛（D-012 垫后余量语义：空闲 ≥ 牌费 + N）=====

    @Test
    fun `门槛叠加牌费 2费卡N=4需空闲6且垫后仍剩4费`() {
        // 放行 ⟺ 空闲 ≥ 2+4=6：空闲 5 捏，空闲 6 放行（垫出后剩 4 费）
        val card = buildCard("T1", cost = 2, powerWeight = 5.0, idleThreshold = 4)
        assertFalse(card.passesSurplusCandidate(remainingCost = 5, isFull = false))
        assertTrue(card.passesSurplusCandidate(remainingCost = 6, isFull = false))
    }

    @Test
    fun `门槛跨卡费同义 1费卡N=1空闲2才垫`() {
        // 同一 N 对任何卡费语义一致：1 费卡 N=1 = 空闲 2 才垫（垫后剩 1 费）——不与牌费心算比较
        val card = buildCard("T1b", cost = 1, powerWeight = 5.0, idleThreshold = 1)
        assertFalse(card.passesSurplusCandidate(remainingCost = 1, isFull = false))
        assertTrue(card.passesSurplusCandidate(remainingCost = 2, isFull = false))
    }

    @Test
    fun `不贪心 配更小的N 更早放行`() {
        val card = buildCard("T2", cost = 2, powerWeight = 5.0, idleThreshold = 2)
        assertFalse(card.passesSurplusCandidate(remainingCost = 3, isFull = false))
        assertTrue(card.passesSurplusCandidate(remainingCost = 4, isFull = false))
    }

    @Test
    fun `未配置门槛随时可垫 无普遍默认档`() {
        // 不配门槛 = N=0：付得起即垫（D-005「无战术不死捏」，持有意愿一律显式配置）
        val card = buildCard("T3", cost = 1, powerWeight = 3.0)
        assertTrue(card.passesSurplusCandidate(remainingCost = 1, isFull = false))
        // 0 费卡空闲 0 也放行（v1 池语义默认 N=1 会捏 0 费卡，D-012 已修正）
        val free = buildCard("T3b", cost = 0, powerWeight = 3.0)
        assertTrue(free.passesSurplusCandidate(remainingCost = 0, isFull = false))
    }

    // ===== 分组级门槛（T-019 SURPLUS_GATE）：一类牌统一捏、不用逐卡设置 =====

    @Test
    fun `分组级门槛生效 垫后仍剩4费才放行`() {
        // 无逐卡门槛、分组 N=4：2 费解牌空闲 5 捏、空闲 6 垫（垫后剩 4 费）
        val card = buildCard("G1", cost = 2, powerWeight = 5.0, groupIdleThreshold = 4)
        assertFalse(card.passesSurplusCandidate(remainingCost = 5, isFull = false))
        assertTrue(card.passesSurplusCandidate(remainingCost = 6, isFull = false))
    }

    @Test
    fun `逐卡小数位优先于分组行为`() {
        // 逐卡 N=2 覆盖分组 N=4：特殊卡比组内其他牌更不贪心
        val card = buildCard(
            "G2", cost = 2,
            powerWeight = 5.0, idleThreshold = 2, groupIdleThreshold = 4
        )
        assertFalse(card.passesSurplusCandidate(remainingCost = 3, isFull = false))
        assertTrue(card.passesSurplusCandidate(remainingCost = 4, isFull = false))
    }

    @Test
    fun `白板 N=0 未配置恒可填`() {
        val card = buildCard("N1", cost = 2, powerWeight = 2.0)
        assertTrue(card.passesSurplusCandidate(remainingCost = 2, isFull = false))
    }

    @Test
    fun `大门槛软死捏 垫后须剩9费`() {
        // 等效 1 费、门槛 9：空闲 < 1+9 一律捏（硬死捏走 Banned）
        val card = buildCard("D", cost = 1, powerWeight = 1.0, idleThreshold = 9)
        assertFalse(card.passesSurplusCandidate(remainingCost = 9, isFull = false))
        assertTrue(card.passesSurplusCandidate(remainingCost = 10, isFull = false))
    }

    @Test
    fun `战术命中绕门`() {
        val card = buildCard(
            "T4", cost = 2,
            powerWeight = 5.0, idleThreshold = 4, tacticalScore = 8.0
        )
        // 门槛 4 但战术已命中：空闲 2 费（2 < 2+4 本该捏）也放行——现在就是战术价值
        assertTrue(card.passesSurplusCandidate(remainingCost = 2, isFull = false))
    }

    // ===== fillValue = 等效费 + 树分×scale（满足一些赚一点）=====

    @Test
    fun `fillValue 零命中等效费_树分线性加溢价`() {
        val base = { ts: Double ->
            buildCard(
                "F", cost = 2,
                powerWeight = 5.0, idleThreshold = 4, tacticalScore = ts
            )
        }
        // scale=0.4：零命中 → E=5.0；ts=5 → 5+2.0=7.0；ts=10 → 5+4.0=9.0
        assertEquals(5.0, base(0.0).surplusFillValue(), 1e-9)
        assertEquals(7.0, base(5.0).surplusFillValue(), 1e-9)
        assertEquals(9.0, base(10.0).surplusFillValue(), 1e-9)
    }

    // ===== T-027（Q-026 §2.1 修正）：战术命中 = ts≠0（不论正负），命中不论 N；极端 -100 = 绝对不打走 isUnUse =====

    @Test
    fun `fillValue 负分象限 恒等式 E加ts乘scale 不再被max抹平`() {
        // E=5、ts=-5 → 5 + (-5)*0.4 = 3.0（删 max 后不加回；负分象限防御性断言）
        assertEquals(3.0, buildCard("F2", cost = 2, powerWeight = 5.0, tacticalScore = -5.0).surplusFillValue(), 1e-9)
    }

    @Test
    fun `ts 负分 命中折价补位 非绝对禁`() {
        // 命中=ts≠0：ts=-2 也是战术立场，余费可垫（fillValue 折价 → 低优先级补位，不浪费剩余费）
        val card = buildCard("NEG1", cost = 2, powerWeight = 5.0, tacticalScore = -2.0)
        assertTrue(card.passesSurplusCandidate(remainingCost = 20, isFull = false))
    }

    @Test
    fun `ts 负分 命中不论N 忽略惜售门槛`() {
        // 命中不论 N：即便配大 N=9（常态惜售），ts≠0 直接放行
        val card = buildCard("NEG2", cost = 2, powerWeight = 5.0, idleThreshold = 9, tacticalScore = -2.0)
        assertTrue(card.passesSurplusCandidate(remainingCost = 20, isFull = false))
    }

    @Test
    fun `ts 负分 nDelta 与否都恒定放行`() {
        // 命中恒定放行，与绝望松门无涉（nDelta 只作用于「无战术立场」的 N 惜售）
        val card = buildCard("NEG3", cost = 2, powerWeight = 5.0, tacticalScore = -2.0)
        assertTrue(card.passesSurplusCandidate(remainingCost = 100, isFull = false, nDelta = 0))
        assertTrue(card.passesSurplusCandidate(remainingCost = 100, isFull = false, nDelta = 9))
    }

    @Test
    fun `ts 负分 命中第一轮降权竞争入口`() {
        // 第一轮：命中不论 N，ts<0 也进主组合——N=0 与 N=1 都进（解「两轮都不进」死局，靠压低 powerWeight 降权）
        assertTrue(buildCard("F3", cost = 2, powerWeight = 5.0, tacticalScore = -2.0).passesFirstRoundCandidate())
        assertTrue(buildCard("F3b", cost = 2, powerWeight = 5.0, idleThreshold = 1, tacticalScore = -2.0).passesFirstRoundCandidate())
    }

    @Test
    fun `绝对不打 -100 不进余费`() {
        // 极端 -100 = 绝对禁（isUnUse 语义）：extPowerWeight=-100 → powerWeight 深负 → passesSecondRoundCandidate 挡死，
        // 与「命中折价补位」的轻度 ts<0 区分
        val card = buildCard("ABS", cost = 2, powerWeight = 5.0, tacticalScore = -100.0)
        card.extPowerWeight = -100.0
        assertFalse(card.passesSurplusCandidate(remainingCost = 20, isFull = false))
        assertFalse(card.passesFirstRoundCandidate())
    }

    // ===== 填充目标 max Σ fillValue =====

    @Test
    fun `同槽位高等效费白板胜亏模垫牌`() {
        val vanilla = buildCard("V", cost = 2, powerWeight = 2.0)   // E=2
        val tactical = buildCard(
            "T5", cost = 2,
            powerWeight = 1.0, idleThreshold = 2
        )                                          // E=1（亏模）
        val result = SurplusFillCombination.findBestCombination(listOf(vanilla, tactical), ableCost = 2)
        assertEquals(listOf(vanilla), result)
    }

    @Test
    fun `白板填不满时垫牌补空隙`() {
        val tactical = buildCard(
            "T6", cost = 2,
            powerWeight = 1.0, idleThreshold = 2
        ) // E=1
        val result = SurplusFillCombination.findBestCombination(listOf(tactical), ableCost = 2)
        assertEquals(listOf(tactical), result)
    }

    @Test
    fun `组合填充保留 1加2填满3费`() {
        val one = buildCard("C1", cost = 1, powerWeight = 1.0)   // fillValue 1
        val two = buildCard("C2", cost = 2, powerWeight = 2.0)   // fillValue 2
        val three = buildCard("C3", cost = 3, powerWeight = 2.0) // fillValue 2
        val result = SurplusFillCombination.findBestCombination(listOf(one, two, three), ableCost = 3)
        assertEquals(setOf(one, two), result.toSet())
    }

    // ===== 端到端：fillSurplusCost 走新目标 =====

    @Test
    fun `主牌选完后余费门槛与费数目标端到端生效`() {
        val main = buildCard("M", cost = 3, powerWeight = 3.0)
        main.extPowerWeight = 5.0
        val tactical = buildCard(
            "T7", cost = 2,
            powerWeight = 5.0, idleThreshold = 4
        ) // 门槛 4：剩余 2 费 < 2+4=6 → 捏
        val result = EndWeightResult(listOf(main, tactical), cost = 5)
        result.processWeightAfter(main)
        result.processWeightAfter(tactical)
        result.findBestCombination()

        // 主牌 3 费（第一轮 N=4>0 且 ts=0 的惜售牌被排除），剩 2 费：门槛未到（需空闲 6），捏住不放
        assertEquals(listOf(main), result.bestCombination)
    }
}
