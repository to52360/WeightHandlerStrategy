package lin.domain.use.plan

import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import condition.createMockCard
import lin.bean.CardCombinedConfig
import lin.bean.CardWeightInfo
import lin.bean.ComboCard
import lin.bean.usePlan.ConditionalStageOverride
import lin.bean.usePlan.GroupUseOverride
import lin.bean.usePlan.PurposeTagId
import lin.bean.usePlan.UseStage
import lin.rule.condition.ConditionLogic
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/**
 * T-013 验证：谓词组挂的**组级行为**对成员生效。
 *
 * 断链背景：OVERRIDE / SURPLUS_GATE 行为只在启动期按静态 groupMap 展开一次并存入
 * `CardCombinedConfig`；谓词组成员运行时才确定、永不在 groupMap 里 →
 * 出牌覆盖（stageOverride / conditionalStage）、余费门槛、意图推导对谓词组成员静默失效。
 *
 * 本测试用 `GroupBehaviorIndex` 直接装配（与 `ConfigBindingStep.build` 同构），
 * 条件逻辑经 GroupMembershipRuntime 注入——均不依赖真实 DB 配置。
 */
class GroupBehaviorRuntimeTest {

    @After
    fun teardown() {
        GroupBehaviorRuntime.clear()
        GroupMembershipRuntime.clear()
    }

    private val spellGroup = PredicateGroupDef("G_SPELL", "cond_spell", includeDerived = false)

    private val isSpell: ConditionLogic = { callCard.card.cardType == CardTypeEnum.SPELL }

    /**
     * 装配谓词组 + 组级行为。
     * 静态预算侧：本测试不走 CardConfigBindingTask，静态部分由 [staticOverride] / [staticGate] 直接注入
     * `CardCombinedConfig`（等价于启动期按静态 groupMap 展开的产物）。
     */
    private fun configureWith(
        overrides: Map<String, GroupUseOverride>,
        gates: Map<String, Int> = emptyMap(),
        includeDerived: Boolean = false
    ) {
        GroupMembershipRuntime.configure(
            listOf(PredicateGroupDef("G_SPELL", "cond_spell", includeDerived))
        ) { isSpell }
        GroupBehaviorRuntime.configure(GroupBehaviorIndex(overrides, gates))
    }

    private fun card(
        id: String = "S1",
        type: CardTypeEnum = CardTypeEnum.SPELL,
        staticGroups: Set<String> = emptySet(),
        staticOverride: GroupUseOverride? = null,
        staticGate: Int? = null,
        purposeTags: Set<PurposeTagId> = emptySet(),
        purposeReplan: Boolean = false,
        staticIntentStage: UseStage? = null
    ): ComboCard = ComboCard(
        card = createMockCard(cardId = id, cardType = type),
        combinedConfig = CardCombinedConfig(
            weightInfo = CardWeightInfo(cardId = id, powerWeight = 0.0),
            groupIds = staticGroups,
            useIntent = if (staticIntentStage != null)
                lin.bean.usePlan.UseIntent(stage = staticIntentStage) else lin.bean.usePlan.UseIntent(),
            conditionalStage = staticOverride?.conditionalStage,
            groupSurplusIdleThreshold = staticGate,
            purposeTags = purposeTags,
            purposeReplanAfterUse = purposeReplan
        )
    )

    @Test
    fun `谓词组挂的 stageOverride 对成员生效`() {
        configureWith(overrides = mapOf("G_SPELL" to GroupUseOverride(stageOverride = UseStage.LAST)))

        val spell = card(type = CardTypeEnum.SPELL)
        val minion = card(id = "M1", type = CardTypeEnum.MINION)

        // 断链修复的核心断言：谓词组（所有法术）的出牌阶段覆盖生效
        assertEquals(UseStage.LAST, spell.useIntent?.stage)
        // 随从不在谓词组 → 无覆盖，回落静态预算（默认 GENERAL）
        assertEquals(UseStage.GENERAL, minion.useIntent?.stage)
    }

    @Test
    fun `谓词组挂的 SURPLUS_GATE 对成员生效`() {
        configureWith(overrides = emptyMap(), gates = mapOf("G_SPELL" to 2))

        val spell = card(type = CardTypeEnum.SPELL)
        val minion = card(id = "M1", type = CardTypeEnum.MINION)

        assertEquals(2, spell.idleThreshold)
        assertEquals(0, minion.idleThreshold)
    }

    @Test
    fun `谓词组挂的 conditionalStage 对成员生效`() {
        val cs = ConditionalStageOverride(conditionId = "cond_x", stage = UseStage.LATE)
        configureWith(overrides = mapOf("G_SPELL" to GroupUseOverride(conditionalStage = cs)))

        val spell = card(type = CardTypeEnum.SPELL)
        val minion = card(id = "M1", type = CardTypeEnum.MINION)

        assertEquals(cs, spell.conditionalStage)
        assertNull(minion.conditionalStage)
    }

    @Test
    fun `静态组声明优先于谓词组`() {
        configureWith(
            overrides = mapOf(
                "G_STATIC" to GroupUseOverride(stageOverride = UseStage.FIRST),
                "G_SPELL" to GroupUseOverride(stageOverride = UseStage.LAST)
            )
        )

        // 同时属于静态组与谓词组：allGroupIds 静态在前 → 静态声明优先
        val both = card(type = CardTypeEnum.SPELL, staticGroups = setOf("G_STATIC"))

        assertEquals(UseStage.FIRST, both.useIntent?.stage)
    }

    @Test
    fun `命中重算不丢静态部分（全量重算是超集）`() {
        // 只有静态组声明了 stage，谓词组无声明 → 重算后静态声明仍然生效（不被谓词覆盖成默认）
        configureWith(overrides = mapOf("G_STATIC" to GroupUseOverride(stageOverride = UseStage.MID)))

        val both = card(type = CardTypeEnum.SPELL, staticGroups = setOf("G_STATIC"))

        assertEquals(UseStage.MID, both.useIntent?.stage)
    }

    @Test
    fun `conditionalStage 口径为「取第一个条件非空」而非「取第一个 override」`() {
        val cs = ConditionalStageOverride(conditionId = "cond_y", stage = UseStage.SETUP)
        configureWith(
            overrides = mapOf(
                // 静态组有 override 但没有 conditionalStage
                "G_STATIC" to GroupUseOverride(stageOverride = UseStage.FIRST),
                // 谓词组才有 conditionalStage
                "G_SPELL" to GroupUseOverride(conditionalStage = cs)
            )
        )

        val both = card(type = CardTypeEnum.SPELL, staticGroups = setOf("G_STATIC"))

        // 若先取 override（拿到 G_STATIC）再读字段，会得 null —— 此处验证不会
        assertEquals(cs, both.conditionalStage)
        // 且 stage 仍由静态组声明优先
        assertEquals(UseStage.FIRST, both.useIntent?.stage)
    }

    @Test
    fun `未命中谓词组时返回静态预算（短路零变化）`() {
        configureWith(
            overrides = mapOf("G_SPELL" to GroupUseOverride(stageOverride = UseStage.LAST)),
            gates = mapOf("G_SPELL" to 3)
        )

        val minion = card(id = "M1", type = CardTypeEnum.MINION, staticGate = 1)

        // 静态预算原样返回，不进 GroupBehaviorRuntime
        assertEquals(UseStage.GENERAL, minion.useIntent?.stage)
        assertEquals(1, minion.idleThreshold)
    }

    @Test
    fun `卡池外的衍生卡经谓词组获得组级行为`() {
        configureWith(
            overrides = mapOf("G_SPELL" to GroupUseOverride(stageOverride = UseStage.LAST)),
            includeDerived = true
        )

        // combinedConfig == null 即卡池外的卡（衍生/发现/随机生成）
        val derived = ComboCard(card = createMockCard(cardId = "D1", cardType = CardTypeEnum.SPELL))

        assertEquals(UseStage.LAST, derived.useIntent?.stage)
    }

    @Test
    fun `未装配 GroupBehaviorRuntime 时降级为静态预算（不抛异常）`() {
        GroupMembershipRuntime.configure(listOf(spellGroup)) { isSpell }
        GroupBehaviorRuntime.clear()

        val spell = card(type = CardTypeEnum.SPELL)

        assertNull(spell.conditionalStage)
        assertNull(spell.groupSurplusIdleThreshold)
        // useIntent 回落到静态预算（未装配 → resolveUseIntent 返回 null）
        assertEquals(UseStage.GENERAL, spell.useIntent?.stage)
    }

    @Test
    fun `重算保留卡级 replan 兜底与标签默认`() {
        configureWith(overrides = mapOf("G_SPELL" to GroupUseOverride(stageOverride = UseStage.LAST)))

        // 卡级声明 replan=true，谓词组 override 未声明该字段 → 应回落卡级 true（不能被吞掉）
        val spell = card(type = CardTypeEnum.SPELL, purposeReplan = true)

        assertEquals(UseStage.LAST, spell.useIntent?.stage)
        assertTrue(spell.useIntent?.replanAfterUse == true)
    }

    @Test
    fun `per 实例缓存：同一实例重复读取结果一致`() {
        configureWith(overrides = mapOf("G_SPELL" to GroupUseOverride(stageOverride = UseStage.LAST)))

        val spell = card(type = CardTypeEnum.SPELL)

        assertEquals(UseStage.LAST, spell.useIntent?.stage)
        assertEquals(UseStage.LAST, spell.useIntent?.stage)
        assertEquals(UseStage.LAST, spell.useIntent?.stage)
    }

    @Test
    fun `不同实例独立判定（谓词结果不串味）`() {
        configureWith(overrides = mapOf("G_SPELL" to GroupUseOverride(stageOverride = UseStage.LAST)))

        val spell = card(type = CardTypeEnum.SPELL)
        val minion = card(id = "M1", type = CardTypeEnum.MINION)

        assertEquals(UseStage.LAST, spell.useIntent?.stage)
        assertEquals(UseStage.GENERAL, minion.useIntent?.stage)
        // 再次读取仍然正确（缓存未串味）
        assertEquals(UseStage.LAST, spell.useIntent?.stage)
        assertEquals(UseStage.GENERAL, minion.useIntent?.stage)
    }
}
