package lin.domain.use.plan

import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import condition.createMockCard
import lin.bean.CardCombinedConfig
import lin.bean.CardWeightInfo
import lin.bean.ComboCard
import lin.bean.groupIds
import lin.bean.usePlan.*
import lin.rule.condition.ConditionLogic
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/**
 * **无谓词组时的静态直通**——卡组没定义谓词组（或未装配）时，ComboCard 的运行时读取
 * 必须**原样返回启动期静态预算**，不产生任何额外计算/分配。
 *
 * 这是「谓词组是可选特性、不得给未使用的卡组加税」的锁定断言：
 * `GroupMembershipRuntime.resolve` 内部短路返回空集 → 下游守卫恒走静态预算分支，
 * 因而**不需要任何「模式」概念**（空集即信号）。
 *
 * 2026-09-03：随 GroupRuntimeMode 体系删除，由原 `GroupRuntimeModeTest` 改写而来。
 * 删掉的是「模式选择与切换」断言——模式被判定为虚假维度（它只是
 * 「predicateGroupIds 是否为空」的别名），切换行为由 `configure`/`clear` 的数据变化天然保证。
 *
 * 谓词组命中时的合并/重算语义由 GroupMembershipRuntimeTest / ComboRuntimeTest /
 * GroupBehaviorRuntimeTest 覆盖。
 */
class StaticBudgetPassthroughTest {

    @After
    fun teardown() {
        ComboRuntime.clear()
        GroupBehaviorRuntime.clear()
        GroupMembershipRuntime.clear()
    }

    private val entries = listOf(CardComboEntry(comboId = "C1", score = 2.0))
    private val bindings = listOf(CardComboUseBinding("C1", setOf("G_A"), setOf("G_B")))
    private val conditionalStage =
        ConditionalStageOverride(conditionId = "cond_x", stage = UseStage.LATE)
    private val staticIntent = UseIntent(stage = UseStage.LATE)

    private val isSpell: ConditionLogic = { callCard.card.cardType == CardTypeEnum.SPELL }

    private fun card(
        type: CardTypeEnum = CardTypeEnum.MINION,
        inCardPool: Boolean = true
    ): ComboCard = ComboCard(
        card = createMockCard(cardId = "C1", cardType = type),
        combinedConfig = if (inCardPool) {
            CardCombinedConfig(
                weightInfo = CardWeightInfo(cardId = "C1", powerWeight = 0.0),
                groupIds = setOf("G_A"),
                useIntent = staticIntent,
                comboEntries = entries,
                comboUseBindings = bindings,
                conditionalStage = conditionalStage,
                groupSurplusIdleThreshold = 2
            )
        } else null
    )

    @Test
    fun `未装配时七项直读静态预算`() {
        GroupMembershipRuntime.clear()

        val c = card()
        assertEquals(emptySet<String>(), c.predicateGroupIds)
        assertEquals(setOf("G_A"), c.groupIds())
        // 直读静态预算**引用**（零计算零分配）——「不加税」的核心断言
        assertSame(entries, c.comboEntries)
        assertSame(bindings, c.comboUseBindings)
        assertSame(conditionalStage, c.conditionalStage)
        assertEquals(2, c.groupSurplusIdleThreshold)
        assertSame(staticIntent, c.useIntent)

        // 卡池外的卡（combinedConfig == null）：全空，即谓词组特性落地前的最初行为
        val derived = card(inCardPool = false)
        assertEquals(emptySet<String>(), derived.groupIds())
        assertTrue(derived.comboEntries.isEmpty())
        assertNull(derived.useIntent)
    }

    @Test
    fun `装配空谓词组列表时同样直通静态预算`() {
        GroupMembershipRuntime.configure(emptyList())

        val c = card()
        assertEquals(emptySet<String>(), c.predicateGroupIds)
        assertSame(entries, c.comboEntries)
    }

    @Test
    fun `装配谓词组后再清空回到静态直通`() {
        GroupMembershipRuntime.configure(
            listOf(PredicateGroupDef("G_SPELL", "cond_spell", includeDerived = false))
        ) { isSpell }

        // 装配期间：命中谓词组的卡，谓词组并入组集合
        val spell = card(type = CardTypeEnum.SPELL)
        assertEquals(setOf("G_A", "G_SPELL"), spell.groupIds())

        // 清空后：同一张法术卡回到最初行为——条件不再求值、直读静态预算引用
        GroupMembershipRuntime.clear()
        val afterClear = card(type = CardTypeEnum.SPELL)
        assertEquals(emptySet<String>(), afterClear.predicateGroupIds)
        assertEquals(setOf("G_A"), afterClear.groupIds())
        assertSame(entries, afterClear.comboEntries)
    }
}
