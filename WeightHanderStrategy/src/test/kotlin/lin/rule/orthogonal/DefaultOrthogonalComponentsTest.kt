package lin.rule.orthogonal

import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import condition.createMockCard
import condition.createMockWarInfo
import condition.fakeRuleEnv
import lin.bean.ComboCard
import lin.rule.context.RuleContext
import lin.rule.context.WarView
import org.junit.Assert.*
import org.junit.Test

/**
 * 重构后的正交组件（WarView 局势数据源、溢出/承受伤害 Transform、分组/用途/类型 Filter）单元测试
 */
class DefaultOrthogonalComponentsTest {

    @Test
    fun testWarViewSourceAndTransforms() {
        val mockWarInfo = createMockWarInfo()
        val mockComboCard = ComboCard(card = createMockCard())
        val context = RuleContext(mockComboCard)
        val env = fakeRuleEnv(mockWarInfo)

        // 1. 测试 WarViewSource 数据源解析
        val warView = WarViewSource.resolve(context, env)
        assertNotNull("WarViewSource 应能解析出非空的 WarView 实例", warView)
        assertTrue("解析出的视图应为 WarView 类型", warView is WarView)

        // 2. 测试 ExcessDamageTransform 提取溢出伤害
        val excessDamage = ExcessDamageTransform.transform(warView)
        assertEquals("初始无对手场面时溢出伤害应为 0", 0, excessDamage)

        // 3. 测试 AcceptableAttackTransform 提取可承受攻击上限
        val acceptableAttack = AcceptableAttackTransform.transform(warView)
        assertEquals("初始可承受攻击上限应计算一致", 0, acceptableAttack)

        // 4. 测试 RivalCardsFromViewTransform & MeCardsFromViewTransform 提取场面随从
        val rivalCards = RivalCardsFromViewTransform.transform(warView)
        val meCards = MeCardsFromViewTransform.transform(warView)
        assertEquals("初始敌方场面随从列表应为空", 0, rivalCards.size)
        assertEquals("初始我方场面随从列表应为空", 0, meCards.size)
    }

    @Test
    fun testTauntAndCardTypeFilterTransform() {
        val tauntMinion = createMockCard(cardId = "TEST_TAUNT", cardType = CardTypeEnum.MINION, isTaunt = true)
        val normalMinion = createMockCard(cardId = "TEST_NORMAL", cardType = CardTypeEnum.MINION, isTaunt = false)
        val spellCard = createMockCard(cardId = "TEST_SPELL", cardType = CardTypeEnum.SPELL, isTaunt = false)

        val cardList = listOf(tauntMinion, normalMinion, spellCard)

        // 1. 测试 TauntFilterTransform
        val tauntList = TauntFilterTransform.transform(cardList)
        assertEquals("应只过滤出 1 张嘲讽随从", 1, tauntList.size)
        assertEquals("TEST_TAUNT", tauntList[0].cardId)

        // 2. 测试 CardTypeFilterTransform (SPELL)
        val spellList = CardTypeFilterTransform.transform(cardList, mapOf("cardType" to CardTypeEnum.SPELL))
        assertEquals("应只过滤出 1 张法术卡牌", 1, spellList.size)
        assertEquals("TEST_SPELL", spellList[0].cardId)
    }

    @Test
    fun testOperators() {
        val spellCard = createMockCard(cardType = CardTypeEnum.SPELL)
        val minionCard = createMockCard(cardType = CardTypeEnum.MINION)

        // 测试 IsCardTypeOp
        val isSpell = IsCardTypeOp.evaluate(spellCard, CardTypeMatchParams(CardTypeEnum.SPELL))
        val isMinionForSpell = IsCardTypeOp.evaluate(spellCard, CardTypeMatchParams(CardTypeEnum.MINION))

        assertTrue("法术卡牌匹配 CardTypeEnum.SPELL 应为 true", isSpell)
        assertTrue("法术卡牌匹配 CardTypeEnum.MINION 应为 false", !isMinionForSpell)

        // 测试 IsEmptyOp & IsNotEmptyOp
        val emptyCol: Collection<*> = emptyList<Any>()
        val nonEmptyCol: Collection<*> = listOf(minionCard)
        assertTrue("空列表判定 is_empty 应为 true", IsEmptyOp.evaluate(emptyCol, Unit))
        assertTrue("非空列表判定 is_not_empty 应为 true", IsNotEmptyOp.evaluate(nonEmptyCol, Unit))
    }
}
