package condition

import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import lin.bean.CardCombinedConfig
import lin.bean.CardWeightInfo
import lin.bean.ComboCard
import lin.domain.MatchActivityKind
import lin.domain.MatchState
import lin.domain.WarInfo
import lin.rule.condition.ConditionPayload
import lin.rule.condition.ConditionRegistry
import lin.rule.condition.PipelineAssembler
import lin.rule.context.RuleContext
import lin.rule.context.RuleEnv
import lin.rule.context.WarView
import lin.rule.context.toWarView
import lin.rule.orthogonal.GreaterThanOrEqualOp
import lin.rule.orthogonal.MatchActivityEventsSource
import lin.rule.orthogonal.TransformCall
import lin.rule.orthogonal.WeightedActivitySumTransform
import lin.serviceLoader.weightRule.utils.war.WarStatus
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Q-2a-6：圣契最小链路测试
 *
 * 管道：match_activity_events → weighted_activity_sum → gte(4)
 * 覆盖 3 个场景。
 */
class MatchActivityPipelineTest {

    private val objectMapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
    private val assembler = PipelineAssembler(
        dataSources = mapOf("match_activity_events" to MatchActivityEventsSource),
        transforms = mapOf("weighted_activity_sum" to WeightedActivitySumTransform),
        operators = mapOf("gte" to GreaterThanOrEqualOp),
        objectMapper = objectMapper
    )

    private val registry = ConditionRegistry(
        providers = emptyList(),
        pipelineAssembler = assembler
    )

    // ── 圣契管道（配置化表达，零硬编码） ──

    private val pipeline = ConditionPayload.PipelineRef(
        sourceId = "match_activity_events",
        transforms = listOf(
            TransformCall(
                "weighted_activity_sum",
                mapOf(
                    "playedCardIds" to listOf("GDB_726", "GDB_728"),
                    "graveyardCardIds" to listOf("GDB_726"),
                    "weightPerEvent" to 1
                )
            )
        ),
        operatorId = "gte",
        operatorArgs = mapOf("threshold" to 4),
        refId = "shengqi_latch_4"
    )

    private val logic by lazy { registry.build(pipeline) }

    // ── helpers ──

    private fun comboCard(cardId: String): ComboCard {
        val card = createMockCard(cardId = cardId, cardType = CardTypeEnum.MINION)
        return ComboCard(
            card = card,
            combinedConfig = CardCombinedConfig(
                weightInfo = CardWeightInfo(cardId = cardId, powerWeight = 0.0)
            )
        )
    }

    /** 创建含打出事件的 [MatchState] */
    private fun matchStateWithPlays(vararg cardIds: String): MatchState {
        val state = MatchState()
        cardIds.forEach { id -> state.recordCardPlayed(comboCard(id)) }
        return state
    }

    /** 创建含墓地卡牌的 [WarInfo]（扩展 createMockWarInfo 追加 graveyardArea） */
    private fun warInfoWithGraveyard(vararg cardIds: String): WarInfo {
        val graveCards = cardIds.map { createMockCard(cardId = it, cardType = CardTypeEnum.WEAPON) }
        val mockWar = createMockWarWithGraveyard(graveCards)
        return object : WarInfo {
            override val war: War = mockWar
            override val handComboCards: List<ComboCard> = emptyList()
            override val canUseCards: List<ComboCard> = emptyList()
            override val playComboCards: List<ComboCard> = emptyList()
            override val infoMap: Map<String, CardCombinedConfig> = emptyMap()
            override val extCost: Int = 0
            override val warStatus: WarStatus get() = throw UnsupportedOperationException()
            override fun cleanPlayByRoundOnce(): Boolean = false
            override fun roundExecuteOnce(registryId: String): Boolean = false
            override fun reloadPlayComboCards() {}
            override fun registerLifecycle(lifecycle: Any) {}
            override fun logoutLifecycle(lifecycle: Any) {}
        }
    }

    private fun ruleEnv(state: MatchState, warInfo: WarInfo): RuleEnv = object : RuleEnv {
        private val evalCache = mutableMapOf<String, Any?>()

        override fun warInfo(): WarInfo = warInfo
        override fun warView(): WarView = warInfo.toWarView()
        override fun matchState() = state

        @Suppress("UNCHECKED_CAST")
        override fun <T> cache(key: String, compute: () -> T): T =
            evalCache.getOrPut(key) { compute() } as T
    }

    // ── 场景 1：只打出不足 4 → false ──

    @Test
    fun `only played cards below threshold returns false`() {
        // GDB_726 + GDB_728 = 2 < 4，墓地空
        val state = matchStateWithPlays("GDB_726", "GDB_728")
        val warInfo = warInfoWithGraveyard() // 空墓地
        val env = ruleEnv(state, warInfo)
        val ctx = RuleContext(comboCard("GDB_726"))

        assertFalse(logic(ctx, env), "expected false: 2 played + 0 graveyard = 2 < 4")
    }

    // ── 场景 2：打出 + 少量墓地仍不足 4 → false ──

    @Test
    fun `played with insufficient graveyard returns false`() {
        // GDB_726 打出 1 次 + GDB_728 打出 1 次 = 2，墓地 GDB_726 ×1 = 1 → 总计 3 < 4
        val state = matchStateWithPlays("GDB_726", "GDB_728")
        val warInfo = warInfoWithGraveyard("GDB_726")
        val env = ruleEnv(state, warInfo)
        val ctx = RuleContext(comboCard("GDB_726"))

        assertFalse(logic(ctx, env), "expected false: 2 played + 1 graveyard = 3 < 4")
    }

    // ── 场景 3：打出 + 墓地达到 4 → true ──

    @Test
    fun `played plus graveyard meets threshold returns true`() {
        // GDB_726 打出×2 + GDB_728 打出 = 3，墓地 GDB_726 ×1 = 1 → 总计 4
        val state = matchStateWithPlays("GDB_726", "GDB_726", "GDB_728")
        val warInfo = warInfoWithGraveyard("GDB_726")
        val env = ruleEnv(state, warInfo)
        val ctx = RuleContext(comboCard("GDB_726"))

        assertTrue(logic(ctx, env), "expected true: 3 played + 1 graveyard = 4 >= 4")
    }

    // ── 场景 4：引用返回验证（同一 RuleEnv 内 List 实例直接复用，零元素创建） ──

    @Test
    fun `MatchActivityEventsSource returns direct list references without creating elements`() {
        val state = matchStateWithPlays("GDB_726", "GDB_728")
        val warInfo = warInfoWithGraveyard("GDB_726")
        val env = ruleEnv(state, warInfo)
        val ctx = RuleContext(comboCard("GDB_726"))

        val map1 = MatchActivityEventsSource.resolve(ctx, env)
        val map2 = MatchActivityEventsSource.resolve(ctx, env)

        val playedList1 = map1[MatchActivityKind.CARD_PLAYED]
        val playedList2 = map2[MatchActivityKind.CARD_PLAYED]
        assertSame(playedList1, playedList2, "CARD_PLAYED list must be the same instance (zero copy)")

        val graveList1 = map1[MatchActivityKind.CARD_GRAVEYARD]
        val graveList2 = map2[MatchActivityKind.CARD_GRAVEYARD]
        assertSame(graveList1, graveList2, "CARD_GRAVEYARD list must be the same instance (zero copy)")
    }
}
