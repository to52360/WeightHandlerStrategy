package lin.domain.result

import condition.createMockCard
import lin.bean.*
import lin.bean.usePlan.CardComboEntry
import lin.bean.usePlan.NegativeScorePolicy
import lin.bean.usePlan.UseIntent
import org.junit.Assert.*
import org.junit.Test

/**
 * T-026：候选门控过滤（(N, ts) 二元组模型，替代三态 CandidatePolicy 枚举）。
 * 第一轮：tacticalScore != 0（战术命中，不论 N）或 N == 0 才进；ts == 0 且 N > 0 则惜售。
 * 第二轮：仅挡硬禁 isUnUse（Q-036/D-020）——负总分仍进候选，值不值由 fillValue 地板
 * （[SurplusFillCombination] FILL_VALUE_FLOOR）判断。
 * N 解析链：逐卡小数位 > 分组行为 > tag 默认 > 0。
 */
class CandidatePolicyFilterTest {

    private fun buildCard(
        n: Int? = null,
        cost: Int = 3,
        tacticalScore: Double = 0.0,
        baseValue: Double = 5.0,
        extPowerWeight: Double = 0.0
    ): ComboCard {
        val info = CardWeightInfo(cardId = "TEST_${n}_${tacticalScore}", powerWeight = 1.0, surplusIdleThreshold = n)
        val card = ComboCard(
            combinedConfig = CardCombinedConfig(
                weightInfo = info,
                useIntent = UseIntent()
            ),
            card = createMockCard(cardId = "TEST_${n}_${tacticalScore}", cost = cost),
            baseValue = baseValue
        )
        card.extPowerWeight = extPowerWeight
        card.tacticalScore = tacticalScore
        return card
    }

    // ── 第一轮 ──

    @Test
    fun `N 未配置无条件通过第一轮`() {
        // N == 0（无惜售诉求）：战术无关直接放行
        assertTrue(buildCard(n = null).passesFirstRoundCandidate())
        // 即使负总分（extPowerWeight 负）也进第一轮，由组合搜索器内部决定是否入选
        assertTrue(buildCard(n = null, extPowerWeight = -10.0).passesFirstRoundCandidate())
    }

    @Test
    fun `N 大于 0 无战术立场才惜售`() {
        // 命中 = ts≠0，不论 N：正负都过；仅 ts==0（无战术立场）且 N>0 才惜售排除
        assertTrue(buildCard(n = 1, tacticalScore = 3.0).passesFirstRoundCandidate())
        assertTrue(buildCard(n = 1, tacticalScore = -2.0).passesFirstRoundCandidate())
        assertFalse(buildCard(n = 1, tacticalScore = 0.0).passesFirstRoundCandidate())
    }

    // ── N 解析链（逐卡 > 分组 > tag 默认 > 0）──

    @Test
    fun `N 解析链 逐卡优先于分组与 tag`() {
        val card = ComboCard(
            combinedConfig = CardCombinedConfig(
                weightInfo = CardWeightInfo("c1", 1.0, surplusIdleThreshold = 2),
                groupSurplusIdleThreshold = 5,
                useIntent = UseIntent(tagDefaultSurplusIdleThreshold = 1)
            ),
            card = createMockCard(cardId = "c1"),
            baseValue = 5.0
        )
        assertEquals(2, card.idleThreshold)
        assertFalse(card.passesFirstRoundCandidate()) // N=2>0 且 ts=0 → 惜售
    }

    @Test
    fun `N 解析链 分组优先于 tag`() {
        val card = ComboCard(
            combinedConfig = CardCombinedConfig(
                weightInfo = CardWeightInfo("c2", 1.0),
                groupSurplusIdleThreshold = 5,
                useIntent = UseIntent(tagDefaultSurplusIdleThreshold = 1)
            ),
            card = createMockCard(cardId = "c2"),
            baseValue = 5.0
        )
        assertEquals(5, card.idleThreshold)
    }

    @Test
    fun `N 解析链 tag 默认兜底`() {
        val card = ComboCard(
            combinedConfig = CardCombinedConfig(
                weightInfo = CardWeightInfo("c3", 1.0),
                useIntent = UseIntent(tagDefaultSurplusIdleThreshold = 1)
            ),
            card = createMockCard(cardId = "c3"),
            baseValue = 5.0
        )
        assertEquals(1, card.idleThreshold)
        assertFalse(card.passesFirstRoundCandidate())
    }

    @Test
    fun `N 解析链 四层皆缺时缺省为 0`() {
        // 第四层（缺省 0）：逐卡 / 分组 / 标签预设三者皆未声明 → N=0（D-012「未配置 = 付得起即垫」）
        val card = ComboCard(
            combinedConfig = CardCombinedConfig(
                weightInfo = CardWeightInfo("c4", 1.0),
                useIntent = UseIntent()
            ),
            card = createMockCard(cardId = "c4"),
            baseValue = 5.0
        )
        assertEquals(0, card.idleThreshold)
        assertTrue(card.passesFirstRoundCandidate()) // N=0 无惜售诉求 → 无条件进第一轮
    }

    @Test
    fun `idleThreshold 字段值即覆盖链结果 无额外覆盖`() {
        // T-FO-015 收敛判据：字段不是「链的额外一层覆盖」，它读的就是链本身——
        // 逐卡层命中时字段 == 逐卡值（不被分组/标签改写）；任一层单独命中时字段 == 该层值。
        assertEquals(
            2,
            ComboCard(
                combinedConfig = CardCombinedConfig(
                    weightInfo = CardWeightInfo("m1", 1.0, surplusIdleThreshold = 2),
                    groupSurplusIdleThreshold = 5,
                    useIntent = UseIntent(tagDefaultSurplusIdleThreshold = 1)
                ),
                card = createMockCard(cardId = "m1"),
                baseValue = 5.0
            ).idleThreshold
        )
        // 与链的编排点返回值恒一致（同一事实两个读取面，收敛后它们必须相等）
        val groupOnly = ComboCard(
            combinedConfig = CardCombinedConfig(
                weightInfo = CardWeightInfo("m2", 1.0),
                groupSurplusIdleThreshold = 5,
                useIntent = UseIntent(tagDefaultSurplusIdleThreshold = 1)
            ),
            card = createMockCard(cardId = "m2"),
            baseValue = 5.0
        )
        assertEquals(groupOnly.resolveIdleThreshold(), groupOnly.idleThreshold)
    }

    // ── 第二轮 ──

    @Test
    fun `第二轮 负总分未硬禁仍通过`() {
        // Q-036：powerWeight > 0 门退役——负总分（亏模）仍进候选，
        // 值不值得垫由 fillValue 地板（搜索层）+ N 门槛 + NegativeScorePolicy 判断
        assertTrue(buildCard(n = null).passesSecondRoundCandidate())
        assertTrue(buildCard(n = null, extPowerWeight = -10.0).passesSecondRoundCandidate())
    }

    @Test
    fun `第二轮 unUse硬禁不通过`() {
        // Banned/打出失败 → extPowerWeight = UnUseWeight(-100) → 候选门唯一硬禁语义
        val card = buildCard(n = null)
        card.unUse()
        assertFalse(card.passesSecondRoundCandidate())
    }

    // ── 余费门槛 ──

    @Test
    fun `passesSurplusGate 战术命中直接放行`() {
        assertTrue(buildCard(n = 3, tacticalScore = 1.0).passesSurplusGate(idleCost = 0))
    }

    @Test
    fun `passesSurplusGate 未命中需空闲达标`() {
        // N=2 的 3 费牌：空闲 ≥ 3+2=5 才垫（垫出后仍须剩 2 费）
        val card = buildCard(n = 2, cost = 3)
        assertFalse(card.passesSurplusGate(idleCost = 4))
        assertTrue(card.passesSurplusGate(idleCost = 5))
    }

    // ── T-028：NegativeScorePolicy ──

    @Test
    fun `AGGRESSIVE ts负分绕N 空闲不够也放行`() {
        // AGGRESSIVE 下 ts<0 绕 N：N=2 的 3 费牌，空闲 4 < 3+2=5 本该 held，但 ts≠0 放行
        val card = ComboCard(
            combinedConfig = CardCombinedConfig(
                weightInfo = CardWeightInfo("agg1", 1.0, surplusIdleThreshold = 2),
                useIntent = UseIntent(negativeScorePolicy = NegativeScorePolicy.AGGRESSIVE)
            ),
            card = createMockCard(cardId = "agg1", cost = 3),
            baseValue = 5.0
        )
        card.tacticalScore = -2.0
        assertTrue(card.passesSurplusGate(idleCost = 4))
    }

    @Test
    fun `AGGRESSIVE ts正分 绕N不变`() {
        // AGGRESSIVE 下 ts>0 也绕 N（与 NORMAL 一致）
        val card = ComboCard(
            combinedConfig = CardCombinedConfig(
                weightInfo = CardWeightInfo("agg2", 1.0, surplusIdleThreshold = 2),
                useIntent = UseIntent(negativeScorePolicy = NegativeScorePolicy.AGGRESSIVE)
            ),
            card = createMockCard(cardId = "agg2", cost = 3),
            baseValue = 5.0
        )
        card.tacticalScore = 3.0
        assertTrue(card.passesSurplusGate(idleCost = 0))
    }

    @Test
    fun `NORMAL默认 ts负分 尊重N`() {
        // 默认 NORMAL 不受影响：ts<0 同 ts==0，尊重 N
        val card = buildCard(n = 2, cost = 3, tacticalScore = -2.0)
        assertFalse(card.passesSurplusGate(idleCost = 4))
        assertTrue(card.passesSurplusGate(idleCost = 5))
    }

    // ── T-PV-008：combo 成员第一轮豁免（D-005 / D-006）──

    @Test
    fun `combo 成员豁免第一轮门槛`() {
        // D-005：combo 成员的价值在组合里（搜索时由 comboBonus 表达），单卡门槛对其无意义 → 豁免进搜索。
        // 对照组：同样的 ts==0 + N=2，非 combo 成员应被惜售挡下。
        assertFalse(buildCard(n = 2, tacticalScore = 0.0).passesFirstRoundCandidate())
        val member = buildComboMemberCard(n = 2, tacticalScore = 0.0)
        assertTrue(member.isComboMember())
        assertTrue(member.passesFirstRoundCandidate())
    }

    @Test
    fun `combo 成员不豁免余费门`() {
        // D-006：只豁免第一轮——配合不成立时 combo 成员不应被余费填充垫出（浪费 core 牌），
        // 故第二轮仍受「空闲 ≥ 牌费 + N」约束（N=2 的 3 费牌需空闲 ≥ 3+2=5）。
        val member = buildComboMemberCard(n = 2, cost = 3, tacticalScore = 0.0)
        assertFalse(member.passesSurplusGate(idleCost = 4))
        assertTrue(member.passesSurplusGate(idleCost = 5))
    }

    private fun buildComboMemberCard(
        n: Int? = null,
        cost: Int = 3,
        tacticalScore: Double = 0.0
    ): ComboCard {
        val card = ComboCard(
            combinedConfig = CardCombinedConfig(
                weightInfo = CardWeightInfo(
                    cardId = "CB_${n}_${tacticalScore}",
                    powerWeight = 1.0,
                    surplusIdleThreshold = n
                ),
                // comboEntries 非空 = combo 成员；谓词组为空时直接取静态预算（无需 ComboRuntime 装配）
                comboEntries = listOf(CardComboEntry(comboId = "combo_test", score = 10.0)),
                useIntent = UseIntent()
            ),
            card = createMockCard(cardId = "CB_${n}_${tacticalScore}", cost = cost),
            baseValue = 5.0
        )
        card.tacticalScore = tacticalScore
        return card
    }
}
