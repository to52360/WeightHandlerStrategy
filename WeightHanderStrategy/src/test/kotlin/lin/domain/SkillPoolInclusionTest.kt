package lin.domain

import lin.bean.*
import lin.domain.use.UseContext
import lin.domain.use.UseDomain
import lin.domain.use.executeAfterAction
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-021a/D-009 技能平权入池：`MyWarManage.skillCandidate()` 使技能进 canUseCards 参与完整竞争。
 *
 * 契约（D-009 第 2/3 条，T-026 修订）：
 * - 未配置技能缺省注入 = 等效费 1 / N=0（D-012：不注门槛全自由，费门天然保证 Q-013 语义）→ 自然入池竞争
 *   （权重 1.0 挤不走高权重手牌牌位），全手牌卡手时「Empty→skillFallbackUse 强用」简化为「自然入池直接选出」；
 * - 配置技能走正常 infoMap 链 → N 由配置声明（默认 0 无惜售）→ 可进第一轮；
 * - 池内打出经 useAfterStrategy 回执置已用标记；标记随 reLoad 周期重置（游戏层一回合一次为硬防线）；
 * - 换英雄（power 变更）被每周期重建天然吸收。
 */
class SkillPoolInclusionTest {

    private val harness = WarManageHarness()

    @Before
    fun setUp() {
        harness.start()
    }

    @After
    fun tearDown() {
        harness.stop()
    }

    @Test
    fun `未配置技能缺省注入自然入池`() {
        val (power, _) = WarManageHarness.powerCard()
        harness.setPower(power)
        harness.reLoad()

        val skill = harness.warManage.canUseCards.single()
        assertEquals(1.0, skill.equivalentCostValue(), "Q-013 缺省等效费 1")
        assertEquals(0, skill.idleThreshold, "缺省不注入门槛 N=0（付得起即垫，费门天然保证 Q-013 语义）")
        assertTrue(skill.passesFirstRoundCandidate(), "N=0 无惜售诉求：自然入池参与第一轮竞争")
        assertTrue(skill.passesSurplusCandidate(remainingCost = 2, isFull = false), "空闲≥N 可垫")
    }

    @Test
    fun `配置技能走正常链 可进第一轮`() {
        val (power, _) = WarManageHarness.powerCard()
        harness.config(power, CardWeightInfo(cardId = power.cardId, powerWeight = 3.0))
        harness.setPower(power)
        harness.reLoad()

        val skill = harness.warManage.canUseCards.single()
        assertEquals(3.0, skill.equivalentCostValue())
        assertTrue(skill.passesFirstRoundCandidate(), "N=0（未配门槛）无惜售诉求，参与第一轮主搜索")
    }

    @Test
    fun `池内打出回执置已用标记，随 reLoad 周期重置后重入池`() {
        val (power, _) = WarManageHarness.powerCard()
        harness.setPower(power)
        harness.reLoad()

        val skill = harness.warManage.canUseCards.single()
        skill.useAfterStrategy?.executeAfterAction(UseContext(skill), UseDomain(harness.warManage))
        assertTrue(harness.warManage.skillUsedThisCycle, "池内打出经回执钩子置已用标记")

        // 周期重置语义（D-009）：标记随 reLoad 清零、技能重入池；重复尝试由游戏层一回合一次硬防线拒绝
        harness.reLoad()
        assertFalse(harness.warManage.skillUsedThisCycle)
        assertEquals(1, harness.warManage.canUseCards.size, "重置后技能重新入池")
    }

    @Test
    fun `实时费不可负担不入池`() {
        val (power, _) = WarManageHarness.powerCard(cost = 2)
        harness.setPower(power)
        harness.setUsableResource(1)
        harness.reLoad()

        assertTrue(harness.warManage.canUseCards.isEmpty(), "费用门：card.cost > usableResource 不入池")
    }

    @Test
    fun `换英雄后池内技能随重建切换`() {
        val (powerA, _) = WarManageHarness.powerCard(cardId = "TEST_POWER_A")
        harness.config(powerA, CardWeightInfo(cardId = powerA.cardId, powerWeight = 3.0))
        harness.setPower(powerA)
        harness.reLoad()
        assertEquals("TEST_POWER_A", harness.warManage.canUseCards.single().card.cardId)

        val (powerB, _) = WarManageHarness.powerCard(cardId = "TEST_POWER_B")
        harness.config(powerB, CardWeightInfo(cardId = powerB.cardId, powerWeight = 3.0))
        harness.setPower(powerB)
        harness.reLoad()
        assertEquals(
            "TEST_POWER_B", harness.warManage.canUseCards.single().card.cardId,
            "每周期重建吸收 power 变更，无需缓存比对"
        )
    }
}
