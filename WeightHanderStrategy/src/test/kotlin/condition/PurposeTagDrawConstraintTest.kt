package condition

import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import lin.bean.CardCombinedConfig
import lin.bean.CardWeightInfo
import lin.bean.ComboCard
import lin.domain.MyWarManage
import lin.rule.handler.EvalOutcome
import lin.rule.handler.EvalSignal
import lin.rule.handler.evaluateCardRoots
import lin.rule.tree.EvaluatorInstanceNode
import lin.warExt.my.base.getHandCards
import org.junit.Test
import sun.misc.Unsafe
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 过牌兜底最小闭环（DECISIONS 结论 3/5）：
 * - Constraint 树（手牌≥9 → BAN）一票否决，穿透到编排层
 * - Score 树（手牌≥7 → -10）独立累加，与 Constraint 树分离
 * - 无 Constraint 时用 score 模拟禁止（-100）不会一票否决，只会被其它加分抵消
 */
class PurposeTagDrawConstraintTest {

    private val unsafe: Unsafe by lazy {
        val field = Unsafe::class.java.getDeclaredField("theUnsafe")
        field.isAccessible = true
        field.get(null) as Unsafe
    }

    /** evaluateCardRoots 的 warManage 参数在函数体内未使用，用 Unsafe 分配空实例即可 */
    private fun mockWarManage(): MyWarManage =
        unsafe.allocateInstance(MyWarManage::class.java) as MyWarManage

    /** 手牌≥9 → BAN 的 Constraint 树 */
    private fun banTree(): EvaluatorInstanceNode.RuleNode =
        EvaluatorInstanceNode.RuleNode("ban_draw") { env ->
            if (env.warInfo().getHandCards().size >= 9) EvalOutcome.Banned
            else EvalOutcome.Skipped(0.0)
        }

    /** 手牌≥7 → -10 的 Score 树 */
    private fun scoreTree(score: Double = -10.0): EvaluatorInstanceNode.RuleNode =
        EvaluatorInstanceNode.RuleNode("score_draw") { env ->
            if (env.warInfo().getHandCards().size >= 7) EvalOutcome.Matched(score)
            else EvalOutcome.Skipped(0.0)
        }

    private fun buildCard(vararg roots: EvaluatorInstanceNode): ComboCard {
        val info = CardWeightInfo(cardId = "TEST_DRAW_001", powerWeight = 1.0)
        roots.forEach { info.addIntentEvaluatorRoot(it) }
        return ComboCard(
            combinedConfig = CardCombinedConfig(weightInfo = info),
            card = createMockCard(cardId = "TEST_DRAW_001")
        )
    }

    private fun ruleEnvWithHand(n: Int) = fakeRuleEnv(
        createMockWarInfo(handCards = List(n) { createMockCard(cardType = CardTypeEnum.SPELL) })
    )

    @Test
    fun `手牌满9时 Constraint 树 BAN 一票否决，score 累加作废`() {
        val card = buildCard(scoreTree(), banTree())
        // score 树先累加 -10，但 ban 树 Banned 异常穿透，最终整卡禁止
        assertFailsWith<EvalSignal.Banned> {
            evaluateCardRoots(card, mockWarManage(), ruleEnvWithHand(9))
        }
    }

    @Test
    fun `手牌8时未触发 BAN，score 树独立累加`() {
        val card = buildCard(banTree(), scoreTree())
        val result = evaluateCardRoots(card, mockWarManage(), ruleEnvWithHand(8))
        assertEquals(-10.0, result.score)
    }

    @Test
    fun `手牌6时两树都不命中，总分为0`() {
        val card = buildCard(banTree(), scoreTree())
        val result = evaluateCardRoots(card, mockWarManage(), ruleEnvWithHand(6))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `无 Constraint 时 score 模拟禁止是错误语义：返回有限负分而非一票否决`() {
        // 只有 score 树，即使用 -100 模拟"手牌满禁止"，也只是负分
        // 若其它树给 +30，净分仍为 -70，卡照常可出——不会像 BAN 那样整卡 unUse
        val card = buildCard(scoreTree(score = -100.0))
        val result = evaluateCardRoots(card, mockWarManage(), ruleEnvWithHand(9))
        assertEquals(-100.0, result.score)
        assertFailsWith<EvalSignal.Banned> {
            // 对照：如果真有 Constraint 树，同样手牌下应抛 Banned
            evaluateCardRoots(buildCard(banTree()), mockWarManage(), ruleEnvWithHand(9))
        }
    }
}
