package lin.math

import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import lin.bean.CardCombinedConfig
import lin.bean.CardWeightInfo
import lin.domain.context.*
import lin.bean.ComboCard
import lin.bean.equivalentCostValue
import lin.bean.surplusFillValue
import lin.weightHandler.calcBaseValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import condition.createMockCard
import kotlin.math.pow

class ScoringModelTest {

    @Test
    fun testScoringPrimitives() {
        assertEquals(0.5, CostWeight, 0.001)
        assertEquals(0.35, PenaltyWeight, 0.001)
        assertEquals(0.1, ComboCardWeight, 0.001)

        val ratio = PenaltyWeight / CostWeight
        assertTrue("PenaltyWeight/CostWeight ratio ($ratio) should be in [0.55, 0.70]", ratio in 0.55..0.70)

        // 剩余法力惩罚
        val p1 = remainingCostPenalty(1, 5)
        assertTrue("Penalty for remaining 1 cost out of 5 should be ~0.1565, got $p1", p1 in 0.15..0.16)

        // 非线性 comboPenalty = (n-1)^1.5 * 0.1
        assertEquals(0.0, comboPenalty(1), 0.001)
        assertEquals(0.1, comboPenalty(2), 0.001)
        assertEquals(2.0.pow(1.5) * 0.1, comboPenalty(3), 0.001)
    }

    @Test
    fun testCostValueConcave() {
        // 凹函数：1费=3.0，4费=6.0，9费=9.0
        assertEquals(3.0, costValue(1.0), 0.001)
        assertEquals(6.0, costValue(4.0), 0.001)
        assertEquals(9.0, costValue(9.0), 0.001)
        // 严格凹：2 * value(1) > value(2)（低费溢价、高费贬值）
        assertTrue("costValue should be strictly concave", costValue(1.0) * 2 > costValue(2.0))
    }

    @Test
    fun testCostValueCap() {
        // 等效费用超过 maxCost(=10) 时封顶，夸张身材（30/30 → 30费等效）不虚高
        assertEquals(costValue(10.0), costValue(30.0), 0.001)
        assertEquals(costValue(10.0), costValue(100.0), 0.001)
        // 封顶值 = 3 * √10 ≈ 9.487
        assertEquals(3.0 * 10.0.pow(0.5), costValue(10.0), 0.001)
    }

    private fun setField(obj: Any, fieldName: String, value: Any?) {
        var cls: Class<*>? = obj.javaClass
        while (cls != null) {
            try {
                val f = cls.getDeclaredField(fieldName)
                f.isAccessible = true
                f.set(obj, value)
                return
            } catch (e: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
    }

    @Test
    fun testBaseValueThreeWayBranch() {
        fun createMinion(
            id: String,
            cost: Int,
            atc: Int,
            health: Int,
            isTaunt: Boolean = false,
            isAura: Boolean = false
        ) = condition.createMockCard(
            cardId = id,
            cardType = CardTypeEnum.MINION,
            cost = cost,
            atc = atc,
            health = health,
            isTaunt = isTaunt
        ).also { card ->
            if (isAura) setField(card, "isAura", true)
        }

        // 1. 白板随从身材梯度（实时身材凹化）：2费2/3 → costValue(2.5)、7费7/7 → costValue(7)
        val v2 = calcBaseValue(createMinion("TEST_2", 2, 2, 3), null, 0)
        val v7 = calcBaseValue(createMinion("TEST_7", 7, 7, 7), null, 0)
        assertEquals(costValue(2.5), v2, 0.001)
        assertEquals(costValue(7.0), v7, 0.001)
        assertTrue("7费大怪身材分应高于2费小怪", v7 > v2)

        // 2. 减费随从防倒挂：光鳐 9费 vs 3费，身材分 = costValue(5) + 1.5(嘲讽+光环)，不依赖费用
        val ray9 = calcBaseValue(createMinion("REV_942_9", 9, 5, 5, isTaunt = true, isAura = true), null, 0)
        val ray3 = calcBaseValue(createMinion("REV_942_3", 3, 5, 5, isTaunt = true, isAura = true), null, 0)
        assertEquals(costValue(5.0) + 1.5, ray9, 0.001)
        assertEquals(ray9, ray3, 0.001)

        // 3. 法术兜底用「数据库初始费用」+ 保守系数：实时费用=0（减费后）、初始费用=4，
        //    兜底分 = SpellCostValueWeight * √4 = 1.5 * 2 = 3.0（比同费随从身材 costValue(4.5) 保守）
        val spell = condition.createMockCard(cardId = "TEST_SPELL", cardType = CardTypeEnum.SPELL, cost = 0)
        assertEquals(SpellCostValueWeight * 2.0, calcBaseValue(spell, null, 4), 0.001)
        assertTrue(
            "法术兜底应比同费随从身材更保守",
            calcBaseValue(spell, null, 4) < calcBaseValue(createMinion("T_4", 4, 4, 5), null, 0)
        )

        // 4. 配置费用优先：powerWeight=5 → costValue(5)，覆盖身材
        val cfgCard =
            condition.createMockCard(cardId = "CFG_5", cardType = CardTypeEnum.MINION, cost = 3, atc = 3, health = 4)
        val cfg = CardCombinedConfig(weightInfo = CardWeightInfo(cardId = "CFG_5", powerWeight = 5.0))
        assertEquals(costValue(5.0), calcBaseValue(cfgCard, cfg, 0), 0.001)
    }

    @Test
    fun testConfigCostReplacesStatNotAdditive() {
        fun createMinion(id: String, atc: Int, health: Int) =
            condition.createMockCard(cardId = id, cardType = CardTypeEnum.MINION, cost = 3, atc = atc, health = health)

        // 无配置：身材分 = costValue((atc+hp)/2)
        val noCfg = createMinion("CFG_BODY", 6, 6)
        val statOnly = calcBaseValue(noCfg, null, 0)
        assertEquals(costValue(6.0), statOnly, 0.001)

        // 配置 powerWeight=3：基础价值 = costValue(3)，完全取代身材分（二选一，不叠加）
        val withCfg = createMinion("CFG_BODY", 6, 6)
        val cfg = CardCombinedConfig(weightInfo = CardWeightInfo(cardId = "CFG_BODY", powerWeight = 3.0))
        val configured = calcBaseValue(withCfg, cfg, 0)

        assertEquals(costValue(3.0), configured, 0.001)
        // 关键断言：配置后不含身材项，且不等于「配置分 + 身材分」的叠加
        assertTrue(
            "配置费用应完全取代身材分（二选一），而非叠加",
            configured != costValue(3.0) + costValue(6.0)
        )
        assertTrue("配置费用较低时不应高于身材兜底（证明未叠加）", configured < statOnly)
    }

    // ================================================================
    // T-FO-012 Phase 0：量纲（评分轴）不变量测试
    // 目的：把「轴方向」钉住，使将来动公式时不会静默漂移。
    // 量纲约定：费（cost）→ 分（score）经 costValue 凹映射；填充层 fillValue 全程待在【费】轴。
    // ================================================================

    /**
     * costValue 在【费 → 分】方向严格单调递增：费越高，折出的分越高（凹 ≠ 不增）。
     * 这条不变量是「分轴」存在的前提——若哪天变平或回折，说明映射形态被改。
     */
    @Test
    fun testCostValueStrictlyMonotonicInCost() {
        var prev = costValue(0.0)
        var cost = 0.5
        while (cost <= CostValueMaxCost) {
            val cur = costValue(cost)
            assertTrue("costValue 应严格递增：costValue($cost)=$cur 未大于前值 $prev", cur > prev)
            prev = cur
            cost += 0.5
        }
        // 封顶之后转为弱单调（相等可接受），但不允许回折
        assertTrue("超上限后不应回折", costValue(30.0) >= costValue(CostValueMaxCost))
    }

    /**
     * 填充层量纲自洽性：fillValue = 等效费 E + ts，两项同为【费】。
     * 固定 ts，E 变大 ⇒ fillValue 变大（且增量恰为 ΔE，证明是直加而非再缩放）。
     */
    @Test
    fun testSurplusFillValueTracksEquivalentCost() {
        val ts = 2.0
        val small = ComboCard(card = createMockCard(cardId = "FILL_S", atc = 2, health = 3)) // E = 2.5
        small.tacticalScore = ts
        val big = ComboCard(card = createMockCard(cardId = "FILL_B", atc = 4, health = 6)) // E = 5.0
        big.tacticalScore = ts

        val eSmall = small.equivalentCostValue()
        val eBig = big.equivalentCostValue()
        assertTrue("等效费应随身材上升：$eSmall < $eBig", eBig > eSmall)

        val fSmall = small.surplusFillValue()
        val fBig = big.surplusFillValue()
        assertTrue("E 变大时 fillValue 必须变大：$fSmall < $fBig", fBig > fSmall)
        assertEquals("fillValue = E + ts（同轴直加，不得再缩放）", eSmall + ts, fSmall, 0.001)
        assertEquals("fillValue = E + ts（同轴直加，不得再缩放）", eBig + ts, fBig, 0.001)
        assertEquals("E 增量应原样传递到 fillValue", eBig - eSmall, fBig - fSmall, 0.001)
    }

    /**
     * 填充层【费】轴：即使配置了等效费（费），fillValue 仍是费轴值（E + ts），不经 costValue 折成分。
     * 与 [calcBaseValue] 的「配置费 → 分」路径形成正交对照（同一份配置费，两条路径两种量纲）。
     */
    @Test
    fun testSurplusFillValueStaysOnCostAxisWithConfiguredCost() {
        val card = createMockCard(cardId = "CFG_FILL", atc = 1, health = 1)
        val config = CardCombinedConfig(weightInfo = CardWeightInfo(cardId = "CFG_FILL", powerWeight = 4.0))
        val comboCard = ComboCard(combinedConfig = config, card = card)
        comboCard.tacticalScore = 1.5

        assertEquals("E 应直接取配置等效费（费）", 4.0, comboCard.equivalentCostValue(), 0.001)
        assertEquals("fillValue 应停在费轴（E + ts）", 5.5, comboCard.surplusFillValue(), 0.001)
        assertEquals(
            "同一份配置费：baseValue 折成分（3.0·√4），fillValue 留在费 —— 两轴并存是**刻意的**：" +
                    "填充层按费算机会成本，主搜索按分竞争（A-合流版 D-FO-005 已把树分换算成分，二者不再矛盾）",
            costValue(4.0), calcBaseValue(card, config, 0), 0.001
        )
        assertTrue("两轴数值不等（凹映射不可逆）", comboCard.surplusFillValue() != calcBaseValue(card, config, 0))
    }

    // ================================================================
    // T-FO-014（D-FO-005 A-合流版）：tacticalContribution 不变量测试
    // 换算 = costValue(E + ts) − costValue(E)；把「树分经凹函数折成分」这一语义钉住。
    // ================================================================

    /** ts == 0 必须恒零——门控语义（ts≠0 / ts>0）与填充层都依赖「未命中 = 零贡献」。 */
    @Test
    fun testTacticalContributionZeroWhenTsZero() {
        assertEquals(0.0, tacticalContribution(0.0, 0.0), 1e-9)
        assertEquals(0.0, tacticalContribution(4.5, 0.0), 1e-9)
        assertEquals(0.0, tacticalContribution(9.0, 0.0), 1e-9)
    }

    /** E 固定、ts > 0 ⇒ 贡献 > 0（命中即加分，不得为负或零）。 */
    @Test
    fun testTacticalContributionPositiveWhenTsPositive() {
        listOf(0.0, 1.0, 2.5, 4.5, 9.0).forEach { e ->
            val c = tacticalContribution(e, 3.2)
            assertTrue("E=$e 时 ts=3.2 的贡献应为正，实际 $c", c > 0.0)
        }
    }

    /**
     * 凹函数边际递减作用于战术收益：同一份 ts，E 越大贡献越小（严格递减）。
     * 这是 A-合流版区别于「朴素版把 ts 当独立白牌」的核心性质。
     */
    @Test
    fun testTacticalContributionDecreasingInEquivalentCost() {
        val ts = 3.2
        val contributions = listOf(1.0, 2.5, 4.5, 6.0, 9.0).map { e -> tacticalContribution(e, ts) }
        for (i in 1 until contributions.size) {
            assertTrue(
                "同一 ts=$ts 应随 E 增大而贡献递减：第 ${i - 1} 项 ${contributions[i - 1]} 未大于第 $i 项 ${contributions[i]}",
                contributions[i - 1] > contributions[i]
            )
        }
    }

    /**
     * 锚点对照：E=4.5、ts=3.2 → 1.9607 分；E=9、ts=3.2 → 1.4785 分（**公式实算值**）。
     *
     * ⚠️ 数字演进：E=9 的贡献原为 **0.4868**（锚点与探针同用 MaxCost=10 ⇒ `E+ts` 被封顶压缩）；
     * T-FO-016 封顶挪位后探针改用 [TacticalMaxCost]（20），贡献变为 **1.4785**。
     * E=4.5 未触封顶 ⇒ 改动前后均为 1.9607（**低/中费牌零变化**）。
     */
    @Test
    fun testTacticalContributionMatchesComputedAnchors() {
        val mid = tacticalContribution(4.5, 3.2)
        val core = tacticalContribution(9.0, 3.2)
        assertEquals("E=4.5、ts=3.2 应折出 1.9607 分（未触封顶，挪位不影响）", 1.9607, mid, 0.001)
        assertEquals("E=9、ts=3.2 应折出 1.4785 分（探针走 TacticalMaxCost，不再被封顶压缩）", 1.4785, core, 0.001)
        assertTrue("核心牌（E=9）的战术贡献应小于中费牌（E=4.5）", core < mid)
    }

    /**
     * 探针上限（T-FO-016）：`TacticalMaxCost` 只挡**异常输入**。
     *
     * 挪位前锚点与探针共用 `MaxCost`(10) ⇒ `E ≥ 10` 的牌差分**恒 0**（战术激励完全失效）；
     * 挪位后只有 `E + ts > TacticalMaxCost`(20) 才封顶。故：
     * ① `ts` 异常极大（50）时贡献被钳在探针上限；② 正常范围 `ts ≤ 4` 完全不受影响。
     */
    @Test
    fun testTacticalContributionCappedByTacticalMaxCostOnlyForAbnormalInput() {
        // ① 只挡异常输入：ts=50 与 ts=5 在 E=19 时同被探针上限钳住（差分只到封顶点为止）
        val abnormal = tacticalContribution(19.0, 50.0)
        val atCap = tacticalContribution(19.0, 5.0)
        assertEquals("ts=50 应被探针上限钳制，与恰好触达上限时相等", atCap, abnormal, 1e-9)
        assertEquals(
            "探针封顶值 = costValue(TacticalMaxCost, maxCost = TacticalMaxCost) − costValue(19)",
            costValue(TacticalMaxCost, maxCost = TacticalMaxCost) - costValue(19.0), abnormal, 1e-9
        )
        assertTrue("探针上限不得回落到 MaxCost 口径（否则 E=19 贡献为 0）", abnormal > 0.0)

        // ② 正常 ts ≤ 4 不受探针上限影响：E=16 + ts=4 = 20 恰在上限内，与不封顶的公式完全一致
        val e = 16.0
        listOf(1.0, 2.4, 3.2, 4.0).forEach { ts ->
            assertEquals(
                "ts=$ts（≤4）时不应触达探针上限",
                costValue(e + ts, maxCost = TacticalMaxCost) - costValue(e), tacticalContribution(e, ts), 1e-9
            )
            assertTrue("ts=$ts 对 E=$e 的贡献应为正", tacticalContribution(e, ts) > 0.0)
        }
    }

    /**
     * §11.4 的「贡献 < ts」需要限定条件：仅当**边际率 < 1**（即 √E > 1.5 ⇒ E > 2.25）时成立。
     * 在 E 很小（尤其 E=0）时凹函数在原点的斜率极大 ⇒ 贡献反而**大于** ts。
     * 这是 A-合流版的真实边界（低费牌的战术收益被凹函数放大），与「高费牌收益被压缩」互为两面。
     */
    @Test
    fun testTacticalContributionBelowTsOnlyAboveMarginalBoundary() {
        val ts = 3.2
        // E ≥ 2.5：边际率 < 1 ⇒ 差分 < ts
        listOf(2.5, 4.5, 6.0).forEach { e ->
            val c = tacticalContribution(e, ts)
            assertTrue("E=$e 时应满足 贡献($c) < ts($ts)", c < ts)
        }
        // E = 0（0 费牌）：原点斜率极大 ⇒ 贡献 > ts
        val zeroCost = tacticalContribution(0.0, ts)
        assertTrue("E=0 时凹函数原点溢价应使贡献($zeroCost) > ts($ts)", zeroCost > ts)
    }

    /** ts 为负（亏模）时贡献为负，且不会因 E+ts 跌破 0 而产出 NaN（下界钳制）。 */
    @Test
    fun testTacticalContributionNegativeTsStaysFinite() {
        val mild = tacticalContribution(4.5, -2.0)
        assertTrue("负 ts 应折出负贡献，实际 $mild", mild < 0.0)
        assertTrue("负 ts 贡献不应为 NaN", !mild.isNaN())

        // E + ts < 0（如 E=1、ts=-4）：钳制到 0 费地板，仍为有限值且不继续变负
        val extreme = tacticalContribution(1.0, -4.0)
        assertTrue("E+ts<0 时不应产出 NaN，实际 $extreme", !extreme.isNaN())
        assertEquals("E+ts<0 应钳制在「亏到 0 费地板」= −costValue(E)", -costValue(1.0), extreme, 1e-9)
    }

    /**
     * 与填充层口径一致性：换算用的 E 必须与 `surplusFillValue` 同源（都取 `equivalentCostValue()`），
     * 且三分支口径可验证——配置等效费 > 实时身材/2 > 实时费。
     */
    @Test
    fun testTacticalContributionUsesSameEquivalentCostAsFillLayer() {
        // 随从（无配置）⇒ E = (atc+hp)/2
        val minion = ComboCard(card = createMockCard(cardId = "AC_M", atc = 4, health = 5))
        assertEquals("随从分支 E = 实时身材 (atc+hp)/2", 4.5, minion.equivalentCostValue(), 0.001)

        // 配置等效费优先于身材
        val cfgCard = createMockCard(cardId = "AC_C", atc = 4, health = 5)
        val cfg = CardCombinedConfig(weightInfo = CardWeightInfo(cardId = "AC_C", powerWeight = 6.0))
        val configured = ComboCard(combinedConfig = cfg, card = cfgCard)
        assertEquals("配置分支优先取等效费", 6.0, configured.equivalentCostValue(), 0.001)

        // 关键：换算函数对该 E 的输出与「直接用 E 值」一致（证明生产侧传的就是这个 E）
        assertEquals(
            "换算基准 E 与填充层同源",
            tacticalContribution(minion.equivalentCostValue(), 3.2),
            tacticalContribution(4.5, 3.2), 1e-9
        )
        assertTrue(
            "配置等效费更高 ⇒ 同一 ts 的换算贡献更小（凹性）",
            tacticalContribution(configured.equivalentCostValue(), 3.2) < tacticalContribution(minion.equivalentCostValue(), 3.2)
        )
    }

    /**
     * T-FO-017 法术锚点统一：法术分支的 E 取**数据库初始费**（[ComboCard.initialCost]），不再是实时费。
     *
     * 回归点：被减费到 0 的法术原先 E=0 ⇒ 战术贡献被凹函数原点溢价放大到 5.367（**可超整卡基础分 4.5**）；
     * 统一后 E=9 ⇒ 贡献回归正常量级 1.4785。同时填充层 `E + ts` 由 3.2 → 12.2（**已批准的连带变更**：
     * 减费法术更倾向被垫出）。
     */
    @Test
    fun testSpellEquivalentCostUsesInitialCostNotRealtimeCost() {
        // 法术：实时费 = 0（被减费），数据库初始费 = 9
        val spell = ComboCard(
            combinedConfig = CardCombinedConfig(
                weightInfo = CardWeightInfo(cardId = "T_FO_017_SPELL", powerWeight = 0.0)
            ),
            card = createMockCard(cardId = "T_FO_017_SPELL", cardType = CardTypeEnum.SPELL, cost = 0),
            initialCost = 9
        )
        assertEquals("法术锚点应取数据库初始费（原行为为实时费 0.0）", 9.0, spell.equivalentCostValue(), 1e-9)

        spell.tacticalScore = 3.2
        assertEquals("填充层随之停在费轴：E + ts = 9 + 3.2", 12.2, spell.surplusFillValue(), 0.001)
        assertEquals(
            "战术贡献回归正常量级（原实时费 E=0 时为 5.367，可超整卡基础分）",
            1.4785, tacticalContribution(spell.equivalentCostValue(), spell.tacticalScore), 0.001
        )

        // 缺失（initialCost = 0，如衍生卡 / 查不到）⇒ 回落实时费，防锚点被抹成 0
        val fallback = ComboCard(
            card = createMockCard(cardId = "T_FO_017_FALLBACK", cardType = CardTypeEnum.SPELL, cost = 3)
        )
        assertEquals("initialCost 缺失时回落实时费", 3.0, fallback.equivalentCostValue(), 1e-9)
    }

    /**
     * ⚠️ 哨兵直通（回归防护，2026-09-23 审查发现）：`ts = UnUseWeight(-100)` 必须**原样返回**。
     *
     * 它经 `total` 抵达 `WeightHandlerDomain.processWeight` 的 `calWeight == UnUseWeight ⇒ unUse()`
     * 硬禁分支。若被换算压成 `−costValue(E)`（约 −9.5），该分支永不命中 ⇒ 绝对禁出的牌静默复活。
     */
    @Test
    fun testTacticalContributionPassesUnUseSentinelThrough() {
        listOf(0.0, 1.0, 4.5, 9.0).forEach { e ->
            assertEquals("哨兵必须原样返回（不得换算），E=$e", UnUseWeight, tacticalContribution(e, UnUseWeight), 1e-9)
        }
    }

    /**
     * NaN / 无穷防护：非有限输入返回 0.0，绝不产出 NaN
     * （NaN 会污染 powerWeight ⇒ `canUse()` 恒 false，牌被静默判为不可用且不带 unUse 标记 = 无痕故障）。
     */
    @Test
    fun testTacticalContributionGuardsNonFiniteInput() {
        assertEquals("ts=NaN 应回落 0.0", 0.0, tacticalContribution(4.5, Double.NaN), 1e-9)
        assertEquals("E=NaN 应回落 0.0", 0.0, tacticalContribution(Double.NaN, 3.2), 1e-9)
        assertEquals("ts=+∞ 应回落 0.0", 0.0, tacticalContribution(4.5, Double.POSITIVE_INFINITY), 1e-9)
        assertEquals("ts=-∞ 应回落 0.0", 0.0, tacticalContribution(4.5, Double.NEGATIVE_INFINITY), 1e-9)
    }
}
