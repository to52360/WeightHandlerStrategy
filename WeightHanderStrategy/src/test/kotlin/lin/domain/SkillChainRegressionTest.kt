package lin.domain

import lin.bean.CardWeightInfo
import lin.bean.passesSurplusCandidate
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 技能链路回归（T-022 建立，T-021b 后收口为最终形态）：
 * 以 [WarManageHarness]（真实 MyWarManage）驱动技能池化与门控。
 *
 * T-021b 后技能无任何池外特殊路径——SkillFindStrategy/尾部 useSkill/池外填充竞争全部退役，
 * 唯一强制使用点 = `MyWarManage.skillFallbackUse`（空结果兜底，「一次机会」防死循环）。
 * 原 @Ignore @defect 用例（Banned 技能被尾部强用）随漏洞路径删除而结构性修复，
 * 以「Banned（unUse）技能不进任何组合」用例接续锁定。
 */
class SkillChainRegressionTest {

    private val harness = WarManageHarness()

    @Before
    fun setUp() {
        harness.start()
    }

    @After
    fun tearDown() {
        harness.stop()
    }

    // ── harness sanity：驱动真实 MyWarManage（池构建/reLoad/registry）──

    @Test
    fun `harness 驱动真实池构建：canUseCards 按费用过滤手牌`() {
        val cheapMinion = mockMinionCard(cost = 3)
        val expensiveMinion = mockMinionCard(cost = 5)
        harness.setHandCards(listOf(cheapMinion, expensiveMinion))
        harness.setUsableResource(4)
        harness.reLoad()

        assertEquals(
            listOf(cheapMinion.cardId), harness.warManage.canUseCards.map { it.cardId() },
            "只有 3 费随从进池（费用门 card.cost <= usableResource）"
        )
    }

    // ── 门控：负分/Banned 不进任何组合 ──

    @Test
    fun `负分技能被余费候选门挡住`() {
        val (power, _) = WarManageHarness.powerCard()
        harness.config(power, skillConfig(surplusIdleThreshold = 2))
        harness.setPower(power)
        harness.reLoad()

        val skill = harness.warManage.parseComboCard(power)
        skill.addWeight(-5.0) // 评估树负分：这局面打出去亏

        assertFalse(
            skill.passesSurplusCandidate(remainingCost = 10, isFull = false),
            "powerWeight ≤ 0 的技能不得垫入余费"
        )
    }

    @Test
    fun `Banned 技能不进任何组合（原尾部强用漏洞的结构性修复锁定）`() {
        val (power, _) = WarManageHarness.powerCard()
        harness.config(power, skillConfig(surplusIdleThreshold = 2))
        harness.setPower(power)
        harness.reLoad()

        val skill = harness.warManage.canUseCards.single()
        skill.unUse() // 评估树 Banned → unUse（extPowerWeight = UnUseWeight）

        assertFalse(skill.canUse(), "Banned 技能 powerWeight < 0")
        assertFalse(
            skill.passesSurplusCandidate(remainingCost = 10, isFull = false),
            "Banned 技能不得进主组合/余费填充；强用路径已随 T-021b 删除（原 @defect use-intent-model/T-021 闭合）"
        )
    }

    @Test
    fun `零分技能按空闲门槛放行，战术命中绕过门槛`() {
        val (power, _) = WarManageHarness.powerCard()
        harness.config(power, skillConfig(surplusIdleThreshold = 5)) // 捏到 5 费
        harness.setPower(power)
        harness.reLoad()

        val skill = harness.warManage.parseComboCard(power)
        // 空闲 2（= 技能费）< N=5 且无战术命中 → 挡
        assertFalse(skill.passesSurplusCandidate(remainingCost = 2, isFull = false))

        // 战术命中（ts > 0）= 现在就是战术价值 → 绕门放行（升级通道）
        skill.tacticalScore = 3.0
        assertTrue(skill.passesSurplusCandidate(remainingCost = 2, isFull = false))
    }

    // ── 空结果兜底（skillFallbackUse，D-009 第 5 条）──

    @Test
    fun `兜底一次机会：首次强用执行并置标记，再次兜底不再执行`() {
        val (power, action) = WarManageHarness.powerCard()
        harness.config(power, skillConfig())
        harness.setPower(power)
        harness.reLoad()

        assertTrue(harness.warManage.skillFallbackUse(), "首次兜底应执行技能")
        assertEquals(1, action.powerAttempts.get())

        assertFalse(harness.warManage.skillFallbackUse(), "一次机会已消耗")
        assertEquals(1, action.powerAttempts.get(), "不得重复执行")
    }

    @Test
    fun `兜底费用门：实时费不够不执行`() {
        val (power, action) = WarManageHarness.powerCard(cost = 2)
        harness.config(power, skillConfig())
        harness.setPower(power)
        harness.setUsableResource(1)
        harness.reLoad()

        assertFalse(harness.warManage.skillFallbackUse(), "费用不够不得强用")
        assertEquals(0, action.powerAttempts.get())
    }

    // ────────────────────────────────────────────────────────────

    /** 技能缺省配置（D-009：等效费 1 + N=2，Q-013 语义） */
    private fun skillConfig(surplusIdleThreshold: Int? = 2) =
        CardWeightInfo(cardId = "PLACEHOLDER", powerWeight = 1.0, surplusIdleThreshold = surplusIdleThreshold)

    private fun mockMinionCard(cost: Int) =
        condition.createMockCard(cardId = "TEST_MINION_$cost", cost = cost)
}
