package lin.domain.result

import condition.createMockCard
import lin.bean.*
import lin.bean.usePlan.NegativeScorePolicy
import lin.bean.usePlan.UseIntent
import org.junit.Assert.*
import org.junit.Test

/**
 * D-007 双费数机会成本模型 v3（T-015）+ D-012 门槛语义 v2：等效费 E（D-014 原语义，不含战术溢价）
 * + 垫后余量门槛 N（放行 ⟺ 空闲 ≥ 牌费 + N，「垫出后空闲池仍须剩 N 费」，跨卡费同义）
 * → fillValue = E + 树分（Q-024 费化后树分即费值，直加）。
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
        tacticalScore: Double = 0.0,
        useIntent: UseIntent = UseIntent()
    ): ComboCard {
        val info = CardWeightInfo(
            cardId = cardId,
            powerWeight = powerWeight,
            surplusIdleThreshold = idleThreshold
        )
        val card = ComboCard(
            combinedConfig = CardCombinedConfig(
                weightInfo = info,
                useIntent = useIntent,
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

    // ===== fillValue = 等效费 + 树分（Q-024 后树分即费值）=====

    @Test
    fun `fillValue 零命中等效费_树分线性加溢价`() {
        val base = { ts: Double ->
            buildCard(
                "F", cost = 2,
                powerWeight = 5.0, idleThreshold = 4, tacticalScore = ts
            )
        }
        // Q-024 费化直加：零命中 → E=5.0；ts=5 → 5+5.0=10.0；ts=10 → 5+10.0=15.0
        assertEquals(5.0, base(0.0).surplusFillValue(), 1e-9)
        assertEquals(10.0, base(5.0).surplusFillValue(), 1e-9)
        assertEquals(15.0, base(10.0).surplusFillValue(), 1e-9)
    }

    // ===== T-028：NegativeScorePolicy 控制 ts<0 在第二轮的语义 =====
    // NORMAL（默认）：ts<0 尊重 N，同 ts==0（N=0 折价补位 / N>0 惜售 held）
    // AGGRESSIVE：ts<0 也绕 N，折价补位

    @Test
    fun `fillValue 负分象限 恒等式 E加ts 不再被max抹平`() {
        // E=5、ts=-5 → 5 + (-5) = 0.0（Q-024 费化后 ts 即费直加；删 max 后不加回；负分象限防御性断言）
        assertEquals(0.0, buildCard("F2", cost = 2, powerWeight = 5.0, tacticalScore = -5.0).surplusFillValue(), 1e-9)
    }

    @Test
    fun `NORMAL默认 ts负分且N0 空闲充裕通过N路径`() {
        // N=0（无门槛），ts=-2 尊重 N：空闲 >= cost+0 即放行（折价补位，不浪费费）
        val card = buildCard("NEG1", cost = 2, powerWeight = 5.0, tacticalScore = -2.0)
        assertTrue(card.passesSurplusCandidate(remainingCost = 20, isFull = false))
    }

    @Test
    fun `NORMAL默认 ts负分且N大于0 空闲不够被held`() {
        // N=2，ts=-2：NORMAL 下 ts<0 尊重 N，空闲 3 < cost 2+N 2=4 → 惜售 held
        val card = buildCard("NEG2", cost = 2, powerWeight = 5.0, idleThreshold = 2, tacticalScore = -2.0)
        assertFalse(card.passesSurplusCandidate(remainingCost = 3, isFull = false))
    }

    @Test
    fun `NORMAL默认 ts负分且N大于0 空闲够才放行`() {
        // N=2，ts=-2：空闲 4 >= cost 2+N 2=4 → 放行（通过 N 路径，非 ts 绕行）
        val card = buildCard("NEG2b", cost = 2, powerWeight = 5.0, idleThreshold = 2, tacticalScore = -2.0)
        assertTrue(card.passesSurplusCandidate(remainingCost = 4, isFull = false))
    }

    @Test
    fun `AGGRESSIVE ts负分绕N 忽略惜售门槛`() {
        // AGGRESSIVE：ts<0 也绕 N，N=9 大门槛也不拦
        val card = buildCard(
            "AGG1", cost = 2, powerWeight = 5.0, idleThreshold = 9, tacticalScore = -2.0,
            useIntent = UseIntent(negativeScorePolicy = NegativeScorePolicy.AGGRESSIVE)
        )
        assertTrue(card.passesSurplusCandidate(remainingCost = 20, isFull = false))
    }

    @Test
    fun `AGGRESSIVE ts负分绕N 空闲极小也放行`() {
        // AGGRESSIVE：ts<0 绕 N，即使空闲 < cost+N 也放行（折价补位优先）
        val card = buildCard(
            "AGG2", cost = 2, powerWeight = 5.0, idleThreshold = 9, tacticalScore = -2.0,
            useIntent = UseIntent(negativeScorePolicy = NegativeScorePolicy.AGGRESSIVE)
        )
        assertTrue(card.passesSurplusCandidate(remainingCost = 2, isFull = false))
    }

    @Test
    fun `AGGRESSIVE ts负分 nDelta无关`() {
        // AGGRESSIVE 下命中恒定放行，nDelta 只作用于 NORMAL 下无立场牌的 N 惜售
        val card = buildCard(
            "AGG3", cost = 2, powerWeight = 5.0, idleThreshold = 9, tacticalScore = -2.0,
            useIntent = UseIntent(negativeScorePolicy = NegativeScorePolicy.AGGRESSIVE)
        )
        assertTrue(card.passesSurplusCandidate(remainingCost = 2, isFull = false, nDelta = 0))
        assertTrue(card.passesSurplusCandidate(remainingCost = 2, isFull = false, nDelta = 9))
    }

    @Test
    fun `ts 负分 第一轮降权竞争入口 不论N不变`() {
        // 第一轮不受 NegativeScorePolicy 影响，ts≠0 恒进（降权竞争入口）
        assertTrue(buildCard("F3", cost = 2, powerWeight = 5.0, tacticalScore = -2.0).passesFirstRoundCandidate())
        assertTrue(buildCard("F3b", cost = 2, powerWeight = 5.0, idleThreshold = 1, tacticalScore = -2.0).passesFirstRoundCandidate())
    }

    @Test
    fun `绝对不打 -100 不进余费`() {
        // 极端 -100 = 绝对禁（isUnUse 语义，Q-036/D-020 后 passesSecondRoundCandidate = !isUnUse 的唯一硬禁挡板）。
        // 第一轮门按 T-027「命中=ts≠0」：-100 也命中（降权竞争入口），绝对不打由 isUnUse 硬禁在候选链前抽离，
        // 本门不负责剔除（与该文件「ts 负分 第一轮降权竞争入口」用例一致）。
        val card = buildCard("ABS", cost = 2, powerWeight = 5.0, tacticalScore = -100.0)
        card.extPowerWeight = -100.0
        assertFalse(card.passesSurplusCandidate(remainingCost = 20, isFull = false))
        assertTrue(card.passesFirstRoundCandidate())
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
