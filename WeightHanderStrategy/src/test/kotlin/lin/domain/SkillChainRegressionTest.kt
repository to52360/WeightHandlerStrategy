package lin.domain

import club.xiaojiawei.hsscriptcardsdk.bean.Card
import lin.bean.CardWeightInfo
import lin.bean.ComboCard
import lin.bean.passesSurplusCandidate
import lin.domain.use.UseContext
import lin.domain.use.UseDomain
import lin.serviceLoader.findCombo.SkillFindStrategy
import org.junit.After
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-022 技能链路回归（D-009 前置，还清 T-016/T-020 单测债）：
 * 以 [WarManageHarness]（真实 MyWarManage）驱动 SkillFindStrategy 生命周期与门控。
 *
 * 回归清单对应：
 * ① 负分技能不进填充（负分动态亏模兜底）
 * ② 换英雄技能变更检测（processSkill 重建）
 * ③ replan/重复调用后不重复用技（afterExtAction 置位防线）
 * ④ Banned 技能不被尾部强用——**当前为已知漏洞**（useSkill 走顶层 useCard 无门控），
 *    用例以正确语义书写并 @Ignore，T-021b 修复后解除（@defect use-intent-model/T-021）。
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
    fun `harness 驱动真实池构建：canUseCards 按费用过滤手牌，技能未入池（T-021a 前现状）`() {
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

    // ── ① 负分技能不进填充（D-007 动态亏模兜底）──

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

    // ── ② 换英雄技能变更检测（processSkill 生命周期）──

    @Test
    fun `换英雄后技能卡重建为新 power，同轮重复调用不重建`() {
        val (powerA, _) = WarManageHarness.powerCard(cardId = "TEST_POWER_A")
        harness.config(powerA, skillConfig())
        harness.setPower(powerA)
        harness.reLoad()

        val strategy = SkillFindStrategy()
        assertFalse(strategy.processSkill(harness.warManage), "首次 processSkill 建卡并返回未使用")

        // 同一轮（registry 未清）重复调用：返回缓存状态，不重建
        strategy.processSkill(harness.warManage)

        // 新回合：reLoad 清 registry，换英雄（power 变更）→ 重建技能卡
        harness.setPower(WarManageHarness.powerCard(cardId = "TEST_POWER_B").first.also {
            harness.config(it, skillConfig())
        })
        harness.reLoad()
        strategy.processSkill(harness.warManage)

        val skillCard = readStrategySkill(strategy)
        assertEquals("TEST_POWER_B", skillCard.card.cardId, "换英雄后技能卡应重建为新 power")
    }

    // ── ③ 用后不重复执行（afterExtAction 置位防线）──

    @Test
    fun `技能使用后 afterExtAction 置位，useSkill 不再重复执行`() {
        val (power, action) = WarManageHarness.powerCard()
        harness.config(power, skillConfig())
        harness.setPower(power)
        harness.reLoad()

        val strategy = SkillFindStrategy()
        strategy.processSkill(harness.warManage)
        val skillCard = readStrategySkill(strategy)

        strategy.useSkill(harness.warManage) // 尾部直用路径执行一次
        assertEquals(1, action.powerAttempts.get(), "第一次 useSkill 应执行技能")

        // T-020 池内路径使用后经 UseAfterStrategy 置位（replan 后防线）
        strategy.afterExtAction(UseContext(skillCard), UseDomain(harness.warManage))
        strategy.useSkill(harness.warManage)
        assertEquals(1, action.powerAttempts.get(), "已用标记置位后不得重复执行")
    }

    // ── ④ Banned 技能不被尾部强用（已知漏洞，T-021b 修复）──

    @Test
    @Ignore("T-021b 修复前红：复现 useSkill 尾部直用漏洞——unUse（Banned）技能经顶层 useCard 无门控仍被执行。修复后移除本注解。@defect use-intent-model/T-021")
    fun `Banned 技能不应被尾部强用`() {
        val (power, action) = WarManageHarness.powerCard()
        harness.config(power, skillConfig())
        harness.setPower(power)
        harness.reLoad()

        val strategy = SkillFindStrategy()
        strategy.processSkill(harness.warManage)
        readStrategySkill(strategy).unUse() // 评估树 Banned → unUse

        strategy.useSkill(harness.warManage)
        assertEquals(0, action.powerAttempts.get(), "unUse（Banned）技能不得被尾部直用执行")
    }

    // ────────────────────────────────────────────────────────────

    /** 技能缺省配置（D-009：等效费 1 + N=2，Q-013 语义） */
    private fun skillConfig(surplusIdleThreshold: Int? = 2) =
        CardWeightInfo(cardId = "PLACEHOLDER", powerWeight = 1.0, surplusIdleThreshold = surplusIdleThreshold)

    private fun mockMinionCard(cost: Int): Card =
        condition.createMockCard(cardId = "TEST_MINION_$cost", cost = cost)

    private fun readStrategySkill(strategy: SkillFindStrategy): ComboCard {
        val field = SkillFindStrategy::class.java.getDeclaredField("skillComboCard")
        field.isAccessible = true
        return field.get(strategy) as ComboCard
    }
}
