package lin.domain.use.plan

import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import condition.createMockCard
import lin.bean.CardCombinedConfig
import lin.bean.CardWeightInfo
import lin.bean.ComboCard
import lin.bean.groupIds
import lin.rule.condition.ConditionLogic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-002 谓词组运行时求值验证。
 *
 * 条件逻辑通过 [GroupMembershipRuntime.configure] 的解析器参数注入，不依赖真实条件树配置，
 * 聚焦成员判定语义本身：
 * - 静态组 ∪ 谓词组的合并；
 * - `includeDerived` 对卡池外卡（`combinedConfig == null`）的控制；
 * - 求值失败 / 条件缺失 → 降级为「不属于该组」，不影响其余组；
 * - per-ComboCard 缓存（同一实例重复读取一致）；
 * - 未装配时行为与改动前一致。
 */
class GroupMembershipRuntimeTest {

    @After
    fun teardown() = GroupMembershipRuntime.clear()

    private fun card(
        id: String,
        type: CardTypeEnum = CardTypeEnum.MINION,
        staticGroups: Set<String> = emptySet(),
        inCardPool: Boolean = true
    ): ComboCard = ComboCard(
        card = createMockCard(cardId = id, cardType = type),
        combinedConfig = if (inCardPool) {
            CardCombinedConfig(
                weightInfo = CardWeightInfo(cardId = id, powerWeight = 0.0),
                groupIds = staticGroups
            )
        } else null
    )

    /** 装配谓词组并注入条件逻辑（条件树 id → 判定逻辑）。 */
    private fun configureWith(
        logic: Map<String, ConditionLogic>,
        vararg defs: PredicateGroupDef
    ) {
        GroupMembershipRuntime.configure(defs.toList()) { logic[it] }
    }

    /**
     * 「是法术」判定：直接读卡的 cardType，与生产配方
     * `evaluating_card → to_card → is_card_type(SPELL)` 等价。
     * 注：[ConditionLogic] 的 receiver 是 RuleContext，故 `callCard` 可直接访问。
     */
    private val isSpell: ConditionLogic = { callCard.card.cardType == CardTypeEnum.SPELL }

    private val always: ConditionLogic = { true }

    @Test
    fun `谓词组与静态组合并`() {
        configureWith(
            mapOf("cond_spell" to isSpell),
            PredicateGroupDef("G_SPELL", "cond_spell", includeDerived = false)
        )

        val spell = card("S1", type = CardTypeEnum.SPELL, staticGroups = setOf("G_STATIC"))
        val minion = card("M1", type = CardTypeEnum.MINION, staticGroups = setOf("G_STATIC"))

        assertEquals(setOf("G_STATIC", "G_SPELL"), spell.groupIds())
        assertEquals(setOf("G_STATIC"), minion.groupIds())
    }

    @Test
    fun `卡池外的卡默认不进谓词组`() {
        configureWith(
            mapOf("cond_spell" to isSpell),
            PredicateGroupDef("G_SPELL", "cond_spell", includeDerived = false)
        )

        // 衍生法术：符合条件但在卡池外 → includeDerived=false 时不纳入
        val derived = card("D1", type = CardTypeEnum.SPELL, inCardPool = false)
        assertEquals(emptySet<String>(), derived.groupIds())
    }

    @Test
    fun `includeDerived 为真时卡池外的卡也纳入`() {
        configureWith(
            mapOf("cond_spell" to isSpell),
            PredicateGroupDef("G_SPELL", "cond_spell", includeDerived = true)
        )

        val derivedSpell = card("D1", type = CardTypeEnum.SPELL, inCardPool = false)
        val derivedMinion = card("D2", type = CardTypeEnum.MINION, inCardPool = false)

        assertEquals(setOf("G_SPELL"), derivedSpell.groupIds())
        assertEquals(emptySet<String>(), derivedMinion.groupIds())
    }

    @Test
    fun `条件求值失败时该组降级为不匹配且不影响其他组`() {
        configureWith(
            mapOf(
                "cond_boom" to { error("故意失败") },
                "cond_spell" to isSpell
            ),
            PredicateGroupDef("G_BOOM", "cond_boom", includeDerived = false),
            PredicateGroupDef("G_SPELL", "cond_spell", includeDerived = false)
        )

        val spell = card("S1", type = CardTypeEnum.SPELL, staticGroups = setOf("G_STATIC"))

        // 失败的组不计入，其余照常
        assertEquals(setOf("G_STATIC", "G_SPELL"), spell.groupIds())
    }

    @Test
    fun `条件 id 不存在时降级为不匹配`() {
        configureWith(
            emptyMap(),
            PredicateGroupDef("G_MISSING", "cond_not_exist", includeDerived = true)
        )

        val spell = card("S1", type = CardTypeEnum.SPELL)
        assertEquals(emptySet<String>(), spell.groupIds())
    }

    @Test
    fun `未装配谓词组时行为与改动前一致`() {
        GroupMembershipRuntime.clear()
        val c = card("M1", staticGroups = setOf("G_A"))
        assertEquals(setOf("G_A"), c.groupIds())
        assertTrue(GroupMembershipRuntime.isEmpty())
    }

    @Test
    fun `per 实例缓存：同一实例重复读取结果一致`() {
        configureWith(
            mapOf("cond_spell" to isSpell),
            PredicateGroupDef("G_SPELL", "cond_spell", includeDerived = false)
        )

        val spell = card("S1", type = CardTypeEnum.SPELL)
        assertEquals(setOf("G_SPELL"), spell.groupIds())
        assertEquals(setOf("G_SPELL"), spell.groupIds())
        assertEquals(setOf("G_SPELL"), spell.groupIds())
    }

    @Test
    fun `多谓词组同时命中`() {
        configureWith(
            mapOf("cond_spell" to isSpell, "cond_all" to always),
            PredicateGroupDef("G_SPELL", "cond_spell", includeDerived = false),
            PredicateGroupDef("G_ALL", "cond_all", includeDerived = false)
        )

        val spell = card("S1", type = CardTypeEnum.SPELL)
        val minion = card("M1", type = CardTypeEnum.MINION)

        assertEquals(setOf("G_SPELL", "G_ALL"), spell.groupIds())
        assertEquals(setOf("G_ALL"), minion.groupIds())
    }

    @Test
    fun `不同实例独立判定（不被其他实例结果污染）`() {
        configureWith(
            mapOf("cond_spell" to isSpell),
            PredicateGroupDef("G_SPELL", "cond_spell", includeDerived = false)
        )

        val spell = card("S1", type = CardTypeEnum.SPELL)
        val minion = card("M1", type = CardTypeEnum.MINION)

        assertEquals(setOf("G_SPELL"), spell.groupIds())
        assertEquals(emptySet<String>(), minion.groupIds())
        // 再次读取仍然正确（缓存未串味）
        assertEquals(setOf("G_SPELL"), spell.groupIds())
        assertEquals(emptySet<String>(), minion.groupIds())
    }
}
