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
 * T-014 验证：静态/谓词双模式**构造期分发**——模式由 [GroupMembershipRuntime.configure] 翻转，
 * ComboCard 查询层零模式 if/else。
 *
 * - 无谓词组（defs 为空）→ 静态模式：七法直读 combinedConfig 静态预算（返回原引用、零计算），
 *   即谓词组特性落地前的最初行为；
 * - 有谓词组 → 谓词模式：命中判定生效、未命中仍返回静态预算引用（per-card 短路是特性逻辑）；
 * - 删光谓词组重新装载 → 自动切回静态模式。
 *
 * 谓词模式下的合并/重算语义由 GroupMembershipRuntimeTest / ComboRuntimeTest /
 * GroupBehaviorRuntimeTest 覆盖，此处只锁定模式选择与切换本身。
 */
class GroupRuntimeModeTest {

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
                useIntent = UseIntent(stage = UseStage.LATE),
                comboEntries = entries,
                comboUseBindings = bindings,
                conditionalStage = conditionalStage,
                groupSurplusIdleThreshold = 2
            )
        } else null
    )

    @Test
    fun `未装配时默认静态模式`() {
        GroupMembershipRuntime.clear()
        assertSame(StaticGroupRuntimeMode, GroupRuntimeModes.current)
    }

    @Test
    fun `无谓词组时静态模式直读静态预算`() {
        GroupMembershipRuntime.configure(emptyList())
        assertSame(StaticGroupRuntimeMode, GroupRuntimeModes.current)

        val c = card()
        assertEquals(emptySet<String>(), c.predicateGroupIds)
        assertEquals(setOf("G_A"), c.groupIds())
        // 直读静态预算引用（零计算）——「最初模式」的核心断言
        assertSame(entries, c.comboEntries())
        assertSame(bindings, c.comboUseBindings())
        assertEquals(conditionalStage, c.conditionalStage())
        assertEquals(2, c.groupSurplusIdleThreshold())
        assertEquals(UseStage.LATE, c.useIntent()?.stage)

        // 卡池外的卡（combinedConfig == null）：静态模式下全空，即 T-002 之前的最初行为
        val derived = card(inCardPool = false)
        assertEquals(emptySet<String>(), derived.groupIds())
        assertNull(derived.useIntent())
    }

    @Test
    fun `装配谓词组时切到谓词模式且命中生效`() {
        GroupMembershipRuntime.configure(
            listOf(PredicateGroupDef("G_SPELL", "cond_spell", includeDerived = false))
        ) { isSpell }
        assertSame(PredicateGroupRuntimeMode, GroupRuntimeModes.current)

        // 命中：谓词组并入组集合（谓词模式经持有者端到端生效）
        val spell = card(type = CardTypeEnum.SPELL)
        assertEquals(setOf("G_A", "G_SPELL"), spell.groupIds())
        // 未命中：仍返回静态预算引用（per-card 短路是特性逻辑，不是模式判别）
        val minion = card(type = CardTypeEnum.MINION)
        assertSame(entries, minion.comboEntries())
    }

    @Test
    fun `删光谓词组重新装载后切回静态模式`() {
        GroupMembershipRuntime.configure(
            listOf(PredicateGroupDef("G_SPELL", "cond_spell", includeDerived = false))
        ) { isSpell }
        assertSame(PredicateGroupRuntimeMode, GroupRuntimeModes.current)

        GroupMembershipRuntime.clear()

        assertSame(StaticGroupRuntimeMode, GroupRuntimeModes.current)
        // 同一张法术卡回到最初行为：条件不再求值、直读静态预算
        val spell = card(type = CardTypeEnum.SPELL)
        assertEquals(emptySet<String>(), spell.predicateGroupIds)
        assertSame(entries, spell.comboEntries())
    }
}
