package lin.domain.use.plan

import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import condition.createMockCard
import lin.bean.CardCombinedConfig
import lin.bean.CardWeightInfo
import lin.bean.ComboCard
import lin.bean.usePlan.CardComboUseBinding
import lin.bean.usePlan.ComboPlanDefinition
import lin.bean.usePlan.ComboRelation
import lin.rule.condition.ConditionLogic
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/**
 * T-012 验证：谓词组（条件定义成员的分组）参与 combo 评分/互斥/排序约束。
 *
 * 断链背景：combo 条目原由静态 groupMap 反查生成，谓词组成员永不在其中 →
 * combo 引用谓词组时评分加分、coreMutex 剪枝、起手互斥、排序约束装配全线静默失效。
 *
 * 本测试聚焦「运行时补全」的语义正确性，条件逻辑经 GroupMembershipRuntime 解析器注入，
 * combo 定义经 ComboRuntime.configure 装配——均不依赖真实 DB 配置。
 */
class ComboRuntimeTest {

    @After
    fun teardown() {
        ComboRuntime.clear()
        GroupMembershipRuntime.clear()
    }

    /** 装配谓词组 + combo 定义（两个 runtime 都与生产路径同构）。 */
    private fun configureWith(
        defs: List<PredicateGroupDef>,
        logic: Map<String, ConditionLogic>,
        comboDefs: List<ComboPlanDefinition>
    ) {
        GroupMembershipRuntime.configure(defs) { logic[it] }
        ComboRuntime.configure(comboDefs)
    }

    private fun card(
        id: String,
        type: CardTypeEnum = CardTypeEnum.MINION,
        staticGroups: Set<String> = emptySet(),
        inCardPool: Boolean = true
    ): ComboCard = ComboCard(
        card = createMockCard(cardId = id, cardType = type),
        // 静态预算：本测试不构造 CardConfigBindingTask，故静态 combo 条目恒空，
        // 静态组归属由 groupIds 表达（与启动期 groupMap 反查等价）
        combinedConfig = if (inCardPool) {
            CardCombinedConfig(
                weightInfo = CardWeightInfo(cardId = id, powerWeight = 0.0),
                groupIds = staticGroups
            )
        } else null
    )

    private val spellGroup = PredicateGroupDef("G_SPELL", "cond_spell", includeDerived = false)

    private val isSpellLogic: ConditionLogic = { callCard.card.cardType == CardTypeEnum.SPELL }

    @Test
    fun `谓词组命中的卡获得该组引用的 combo 条目`() {
        configureWith(
            defs = listOf(spellGroup),
            logic = mapOf("cond_spell" to isSpellLogic),
            comboDefs = listOf(
                ComboPlanDefinition(
                    id = "C_SPELL", coreGroupIds = setOf("G_SPELL"),
                    depGroupIds = setOf("G_OTHER"), score = 5.0
                )
            )
        )

        val spell = card("S1", type = CardTypeEnum.SPELL)
        val minion = card("M1", type = CardTypeEnum.MINION)

        // 断链修复的核心断言：法术（经谓词组 G_SPELL 命中）拿到 combo 条目
        assertEquals(1, spell.comboEntries.size)
        assertEquals("C_SPELL", spell.comboEntries.first().comboId)
        assertEquals(5.0, spell.comboEntries.first().score, 0.0)
        // 随从不在此谓词组 → 无条目
        assertTrue(minion.comboEntries.isEmpty())
    }

    @Test
    fun `coreMutex 本组过滤覆盖谓词组`() {
        configureWith(
            defs = listOf(spellGroup),
            logic = mapOf("cond_spell" to isSpellLogic),
            comboDefs = listOf(
                // core 含静态组与谓词组：卡同时属于两者时互斥判定须包含谓词组
                ComboPlanDefinition(
                    id = "C_MIX", coreGroupIds = setOf("G_STATIC", "G_SPELL"),
                    depGroupIds = emptySet(), coreMutex = true
                )
            )
        )

        val both = card("S1", type = CardTypeEnum.SPELL, staticGroups = setOf("G_STATIC"))

        val own = both.comboEntries.first().coreMutexOwnGroupIds
        // 运行时过滤基于 allGroupIds（静态 ∪ 谓词）→ 两组都在
        assertEquals(setOf("G_STATIC", "G_SPELL"), own.toSet())
    }

    @Test
    fun `静态组与谓词组共引用一个 combo 时按 comboId 归并`() {
        configureWith(
            defs = listOf(spellGroup),
            logic = mapOf("cond_spell" to isSpellLogic),
            comboDefs = listOf(
                ComboPlanDefinition(
                    id = "C_BOTH", coreGroupIds = setOf("G_STATIC"),
                    depGroupIds = setOf("G_SPELL"), score = 3.0
                )
            )
        )

        val both = card("S1", type = CardTypeEnum.SPELL, staticGroups = setOf("G_STATIC"))

        // 卡同时命中 core 静态组与 dep 谓词组 → 仍只归并出一条（不重复计分）
        val entries = both.comboEntries
        assertEquals(1, entries.size)
        assertEquals("C_BOTH", entries.first().comboId)
    }

    @Test
    fun `未命中谓词组时返回静态预算（短路零变化）`() {
        configureWith(
            defs = listOf(spellGroup),
            logic = mapOf("cond_spell" to isSpellLogic),
            comboDefs = listOf(
                ComboPlanDefinition(
                    id = "C_SPELL", coreGroupIds = setOf("G_SPELL"), depGroupIds = emptySet()
                )
            )
        )

        val minion = card("M1", type = CardTypeEnum.MINION, staticGroups = setOf("G_STATIC"))

        // 静态预算为空（本测试未装配静态条目）→ 短路后仍为空，不进 ComboRuntime
        assertTrue(minion.comboEntries.isEmpty())
        assertTrue(minion.comboUseBindings.isEmpty())
    }

    @Test
    fun `卡池外的衍生卡经谓词组参与 combo`() {
        configureWith(
            defs = listOf(PredicateGroupDef("G_SPELL", "cond_spell", includeDerived = true)),
            logic = mapOf("cond_spell" to isSpellLogic),
            comboDefs = listOf(
                ComboPlanDefinition(
                    id = "C_SPELL", coreGroupIds = setOf("G_SPELL"), depGroupIds = emptySet(), score = 4.0
                )
            )
        )

        // combinedConfig == null 即卡池外的卡（衍生/发现/随机生成）
        val derived = card("D1", type = CardTypeEnum.SPELL, inCardPool = false)

        // 消解 Q-001 专项原「衍生卡不参与 combo 加分」的已知边界
        assertEquals(1, derived.comboEntries.size)
        assertEquals("C_SPELL", derived.comboEntries.first().comboId)
    }

    @Test
    fun `未装配 ComboRuntime 时谓词组不产生条目（降级不抛异常）`() {
        GroupMembershipRuntime.configure(listOf(spellGroup)) { isSpellLogic }
        ComboRuntime.clear()

        val spell = card("S1", type = CardTypeEnum.SPELL)

        assertFalse(ComboRuntime.isReady())
        assertTrue(spell.comboEntries.isEmpty())
        assertTrue(spell.comboUseBindings.isEmpty())
    }

    @Test
    fun `谓词组参与出牌顺序绑定`() {
        configureWith(
            defs = listOf(spellGroup),
            logic = mapOf("cond_spell" to isSpellLogic),
            comboDefs = listOf(
                ComboPlanDefinition(
                    id = "C_ORDER", coreGroupIds = setOf("G_SPELL"),
                    depGroupIds = setOf("G_OTHER"), relation = ComboRelation.CORE_BEFORE_DEP
                )
            )
        )

        val spell = card("S1", type = CardTypeEnum.SPELL)
        val minion = card("M1", type = CardTypeEnum.MINION)

        val bindings = spell.comboUseBindings
        assertEquals(1, bindings.size)
        assertEquals(CardComboUseBinding("C_ORDER", setOf("G_SPELL"), setOf("G_OTHER")), bindings.first())
        // 顺序绑定由 relation 生成，只有命中谓词组的卡才有
        assertTrue(minion.comboUseBindings.isEmpty())
    }

    @Test
    fun `per 实例缓存：同一实例重复读取结果一致`() {
        configureWith(
            defs = listOf(spellGroup),
            logic = mapOf("cond_spell" to isSpellLogic),
            comboDefs = listOf(
                ComboPlanDefinition(
                    id = "C_SPELL", coreGroupIds = setOf("G_SPELL"), depGroupIds = emptySet()
                )
            )
        )

        val spell = card("S1", type = CardTypeEnum.SPELL)

        assertEquals(1, spell.comboEntries.size)
        assertEquals(1, spell.comboEntries.size)
        assertEquals(1, spell.comboEntries.size)
    }

    @Test
    fun `不同实例独立判定（谓词结果不串味）`() {
        configureWith(
            defs = listOf(spellGroup),
            logic = mapOf("cond_spell" to isSpellLogic),
            comboDefs = listOf(
                ComboPlanDefinition(
                    id = "C_SPELL", coreGroupIds = setOf("G_SPELL"), depGroupIds = emptySet()
                )
            )
        )

        val spell = card("S1", type = CardTypeEnum.SPELL)
        val minion = card("M1", type = CardTypeEnum.MINION)

        assertEquals(1, spell.comboEntries.size)
        assertTrue(minion.comboEntries.isEmpty())
        // 再次读取仍然正确（缓存未串味）
        assertEquals(1, spell.comboEntries.size)
        assertTrue(minion.comboEntries.isEmpty())
    }
}
