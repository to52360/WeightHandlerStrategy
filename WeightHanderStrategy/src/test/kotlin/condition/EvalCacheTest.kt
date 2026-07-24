package condition

import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import lin.bean.CardCombinedConfig
import lin.bean.CardWeightInfo
import lin.bean.ComboCard
import lin.domain.MatchActivityKind
import lin.domain.MatchState
import lin.domain.WarInfo
import lin.rule.context.RuleContext
import lin.rule.context.RuleEnv
import lin.rule.context.WarView
import lin.rule.context.toWarView
import lin.rule.orthogonal.MatchActivityEventsSource
import lin.rule.orthogonal.WarViewSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class EvalCacheTest {

    private fun comboCard(cardId: String): ComboCard {
        val card = createMockCard(cardId = cardId, cardType = CardTypeEnum.MINION)
        return ComboCard(
            card = card,
            combinedConfig = CardCombinedConfig(
                weightInfo = CardWeightInfo(cardId = cardId, powerWeight = 0.0)
            )
        )
    }

    private fun createTestEnv(
        state: MatchState = MatchState(),
        graveyardCardsSupplier: () -> List<Card> = { emptyList() }
    ): RuleEnv {
        val baseMockWarInfo = createMockWarInfo()
        return object : RuleEnv {
            private val evalCache = mutableMapOf<String, Any?>()

            override fun warInfo(): WarInfo = object : WarInfo by baseMockWarInfo {
                override val war: War get() = createMockWarWithGraveyard(graveyardCardsSupplier())
            }

            override fun warView(): WarView = cache("war_view") { warInfo().toWarView() }
            override fun matchState(): MatchState = state

            @Suppress("UNCHECKED_CAST")
            override fun <T> cache(key: String, compute: () -> T): T =
                evalCache.getOrPut(key) { compute() } as T
        }
    }

    // ── 用例 1：MatchActivityEventsSource 引用一致性验证 ──

    @Test
    fun `MatchActivityEventsSource returns identical inner list references without eval cache`() {
        val state = MatchState()
        state.recordCardPlayed(comboCard("GDB_726"))
        val env = createTestEnv(state)
        val context = RuleContext(comboCard("HAND_01"))

        val first = MatchActivityEventsSource.resolve(context, env)
        val second = MatchActivityEventsSource.resolve(context, env)

        assertSame(
            "MatchActivityEventsSource 内的 CARD_PLAYED List 应当为同一引用",
            first[MatchActivityKind.CARD_PLAYED],
            second[MatchActivityKind.CARD_PLAYED]
        )
    }

    // ── 用例 2：WarViewSource 评估级缓存命中验证 ──

    @Test
    fun `WarViewSource uses RuleEnv cache within same eval env`() {
        val env = createTestEnv()
        val context1 = RuleContext(comboCard("HAND_CARD_A"))
        val context2 = RuleContext(comboCard("HAND_CARD_B"))

        val res1 = WarViewSource.resolve(context1, env)
        val res2 = WarViewSource.resolve(context2, env)

        assertSame("同一 RuleEnv 内多次 resolve WarViewSource 应命中评估级缓存", res1, res2)
    }

    // ── 用例 3：RuleEnv cache 通用机制 ──

    @Test
    fun `RuleEnv cache caches computed results within same eval env`() {
        val env = createTestEnv()
        var computeCount = 0

        val res1 = env.cache("test_key") { ++computeCount }
        val res2 = env.cache("test_key") { ++computeCount }

        assertEquals(1, computeCount)
        assertEquals(1, res1)
        assertEquals(1, res2)
    }
}
