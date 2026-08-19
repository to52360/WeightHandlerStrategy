package condition

import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import lin.bean.CardCombinedConfig
import lin.bean.CardWeightInfo
import lin.bean.ComboCard
import lin.bean.usePlan.ScoreChannel
import lin.config.EvaluatorTreeRoot
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

    private fun buildCard(vararg roots: EvaluatorTreeRoot): ComboCard {
        val info = CardWeightInfo(cardId = "TEST_DRAW_001", powerWeight = 1.0)
        roots.forEach { info.addIntentEvaluatorRoot(it) }
        return ComboCard(
            combinedConfig = CardCombinedConfig(weightInfo = info),
            card = createMockCard(cardId = "TEST_DRAW_001")
        )
    }

    /** 包装根节点为默认 GENERAL 通道（兼容旧构造） */
    private fun generalRoot(root: EvaluatorInstanceNode): EvaluatorTreeRoot =
        EvaluatorTreeRoot(root = root, channel = ScoreChannel.GENERAL)

    /** 战术通道包装 */
    private fun tacticalRoot(root: EvaluatorInstanceNode): EvaluatorTreeRoot =
        EvaluatorTreeRoot(root = root, channel = ScoreChannel.TACTICAL)

    private fun ruleEnvWithHand(n: Int) = fakeRuleEnv(
        createMockWarInfo(handCards = List(n) { createMockCard(cardType = CardTypeEnum.SPELL) })
    )

    @Test
    fun `手牌满9时 Constraint 树 BAN 一票否决，score 累加作废`() {
        val card = buildCard(generalRoot(scoreTree()), generalRoot(banTree()))
        // score 树先累加 -10，但 ban 树 Banned 异常穿透，最终整卡禁止
        assertFailsWith<EvalSignal.Banned> {
            evaluateCardRoots(card, mockWarManage(), ruleEnvWithHand(9))
        }
    }

    @Test
    fun `手牌8时未触发 BAN，score 树独立累加`() {
        val card = buildCard(generalRoot(banTree()), generalRoot(scoreTree()))
        val result = evaluateCardRoots(card, mockWarManage(), ruleEnvWithHand(8))
        assertEquals(-10.0, result.score)
    }

    @Test
    fun `手牌6时两树都不命中，总分为0`() {
        val card = buildCard(generalRoot(banTree()), generalRoot(scoreTree()))
        val result = evaluateCardRoots(card, mockWarManage(), ruleEnvWithHand(6))
        assertEquals(0.0, result.score)
    }

    @Test
    fun `无 Constraint 时 score 模拟禁止是错误语义：返回有限负分而非一票否决`() {
        // 只有 score 树，即使用 -100 模拟"手牌满禁止"，也只是负分
        // 若其它树给 +30，净分仍为 -70，卡照常可出——不会像 BAN 那样整卡 unUse
        val card = buildCard(generalRoot(scoreTree(score = -100.0)))
        val result = evaluateCardRoots(card, mockWarManage(), ruleEnvWithHand(9))
        assertEquals(-100.0, result.score)
        assertFailsWith<EvalSignal.Banned> {
            // 对照：如果真有 Constraint 树，同样手牌下应抛 Banned
            evaluateCardRoots(buildCard(generalRoot(banTree())), mockWarManage(), ruleEnvWithHand(9))
        }
    }

    // ── T-007：评分通道分离（Q-008）──

    @Test
    fun `战术树分单独进tacticalScore不进generalScore`() {
        val card = buildCard(tacticalRoot(scoreTree(score = 5.0)))
        val result = evaluateCardRoots(card, mockWarManage(), ruleEnvWithHand(7))
        assertEquals(0.0, result.generalScore)
        assertEquals(5.0, result.tacticalScore)
        assertEquals(5.0, result.score)
    }

    @Test
    fun `普通树分进generalScore不进tacticalScore`() {
        val card = buildCard(generalRoot(scoreTree(score = 3.0)))
        val result = evaluateCardRoots(card, mockWarManage(), ruleEnvWithHand(7))
        assertEquals(3.0, result.generalScore)
        assertEquals(0.0, result.tacticalScore)
        assertEquals(3.0, result.score)
    }

    @Test
    fun `混合通道各自累加互不串扰`() {
        val card = buildCard(
            generalRoot(scoreTree(score = 3.0)),
            tacticalRoot(scoreTree(score = 5.0)),
            generalRoot(scoreTree(score = -1.0))
        )
        val result = evaluateCardRoots(card, mockWarManage(), ruleEnvWithHand(7))
        assertEquals(2.0, result.generalScore)      // 3 + (-1)
        assertEquals(5.0, result.tacticalScore)     // 5
        assertEquals(7.0, result.score)             // 总和
    }
}
