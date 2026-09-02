package lin.domain.result

import condition.createMockCard
import condition.createMockWarInfo
import lin.bean.CardCombinedConfig
import lin.bean.CardWeightInfo
import lin.bean.ComboCard
import lin.bean.passesSurplusCandidate
import lin.bean.usePlan.UseIntent
import lin.domain.WarInfo
import lin.domain.parseDespairLadder
import lin.domain.surplusDespairNDelta
import org.junit.Assert.*
import org.junit.Test

/**
 * D-011 全局绝望规则 v2（Q-022 收敛）：布尔前置（场面承压 excessDamage>0 且 ≥ ableAtcSum
 * + 手牌无 N>0 惜售牌）× 血量阶梯幅度（nDelta，门控按 D-012 减 N，地板 0）。
 *
 * 边界锚点：绝望 =「按严重度松 N 门槛」，不等于「主动亏模也垫」——fillValue 非正
 * （FILL_VALUE_FLOOR 剔除，Q-036/D-020 后亏模判断移交搜索层）、满场随从不豁免；
 * 只松第二轮 N 门槛，不动第一轮资格轴（N>0 惜售牌排除照旧）。
 *
 * 门槛语义（D-012，用户问证锚点）：放行 ⟺ 空闲 ≥ 牌费 + N（N=「垫出后仍须剩 N 费」，跨卡费同义）。
 */
class SurplusDespairGateTest {

    private fun buildCard(
        cardId: String,
        cost: Int,
        powerWeight: Double,
        idleThreshold: Int? = null
    ): ComboCard {
        val info = CardWeightInfo(
            cardId = cardId,
            powerWeight = powerWeight,
            surplusIdleThreshold = idleThreshold
        )
        val card = ComboCard(
            combinedConfig = CardCombinedConfig(
                weightInfo = info,
                useIntent = UseIntent()
            ),
            card = createMockCard(cardId = cardId, cost = cost)
        )
        card.extPowerWeight = 1.0
        return card
    }

    // ===== 门控层：nDelta 阶梯减门，地板 0（D-012 门槛 = 牌费 + N）=====

    @Test
    fun `门槛叠加牌费 5费牌N=1空闲6才考虑`() {
        // D-012：放行 ⟺ 空闲 ≥ 5+1 = 6（垫出后仍剩 1 费）——不是空闲 5
        val card = buildCard("I1", cost = 5, powerWeight = 5.0, idleThreshold = 1)
        assertFalse(card.passesSurplusCandidate(remainingCost = 5, isFull = false))
        assertTrue(card.passesSurplusCandidate(remainingCost = 6, isFull = false))
    }

    @Test
    fun `空闲4且N=3仍不能竞争 垫后须剩3费`() {
        // 2 费卡 N=3：空闲 ≥ 2+3=5 才放行（v1 池语义下 N≤牌费是空设，D-012 后所有 N 都生效）
        val card = buildCard("I2", cost = 2, powerWeight = 5.0, idleThreshold = 3)
        assertFalse(card.passesSurplusCandidate(remainingCost = 4, isFull = false))
        assertTrue(card.passesSurplusCandidate(remainingCost = 5, isFull = false))
    }

    @Test
    fun `nDelta按档松门 N=4减1空闲5放行`() {
        // 2 费卡 N=4：常态空闲 ≥ 6 才放行；nDelta=1 → 2+3=5
        val card = buildCard("D1", cost = 2, powerWeight = 5.0, idleThreshold = 4)
        assertFalse(card.passesSurplusCandidate(remainingCost = 5, isFull = false))
        assertTrue(card.passesSurplusCandidate(remainingCost = 5, isFull = false, nDelta = 1))
    }

    @Test
    fun `nDelta地板0 付得起即垫`() {
        // 1 费卡 N=4 减 9 → 1 + max(0, -5) = 1：空闲 1 即放行；地板 0 由「未配置=0」表达（D-005）
        val card = buildCard("D2", cost = 1, powerWeight = 5.0, idleThreshold = 4)
        assertFalse(card.passesSurplusCandidate(remainingCost = 1, isFull = false))
        assertTrue(card.passesSurplusCandidate(remainingCost = 1, isFull = false, nDelta = 9))
    }

    @Test
    fun `未配置门槛nDelta不生效 本就付得起即垫`() {
        val card = buildCard("D2b", cost = 2, powerWeight = 5.0)
        assertFalse(card.passesSurplusCandidate(remainingCost = 1, isFull = false))
        assertTrue(card.passesSurplusCandidate(remainingCost = 2, isFull = false, nDelta = 0))
        assertTrue(card.passesSurplusCandidate(remainingCost = 2, isFull = false, nDelta = 9))
    }

    @Test
    fun `nDelta松门对非硬禁负分生效 亏模判断移交fillValue地板`() {
        // Q-036/D-020：passesSecondRoundCandidate 退役 powerWeight>0 权重算术，改 !isUnUse()。
        // 树/规则负分（powerWeight ≤ 0 但未 unUse 硬禁）不再被第二轮门挡；「这局打出亏不垫」的判断
        // 移交填充搜索层 FILL_VALUE_FLOOR（fillValue ≤ 0 剔除，SurplusGateFillTest /
        // SkillChainRegressionTest 覆盖），nDelta 不与之交互。
        // 本例只锁定：绝望 nDelta 对非硬禁负分牌仍正常松 N（N=4 → nDelta=9 减到 0，付得起即垫进候选池）。
        val card = buildCard("D3", cost = 2, powerWeight = 5.0, idleThreshold = 4)
        card.extPowerWeight = -1.0 // 树/规则负分：powerWeight = -1（非硬禁，未 unUse）
        assertFalse(card.passesSurplusCandidate(remainingCost = 3, isFull = false)) // 常态 N=4：需空闲 6
        assertTrue(
            card.passesSurplusCandidate(remainingCost = 3, isFull = false, nDelta = 9),
            "非硬禁负分不再被第二轮门挡：nDelta=9 → N=0 → 空闲 3 ≥ 2 放行进候选池"
        )
    }

    @Test
    fun `nDelta不豁免满场随从排除`() {
        val card = buildCard("D4", cost = 2, powerWeight = 5.0, idleThreshold = 4)
        assertFalse(card.passesSurplusCandidate(remainingCost = 3, isFull = true, nDelta = 9))
    }

    @Test
    fun `战术命中绕门不受nDelta影响`() {
        val card = buildCard("D5", cost = 2, powerWeight = 5.0, idleThreshold = 9)
        card.tacticalScore = 5.0
        assertTrue(card.passesSurplusCandidate(remainingCost = 2, isFull = false))
        assertTrue(card.passesSurplusCandidate(remainingCost = 2, isFull = false, nDelta = 0))
    }

    // ===== 阶梯解析 =====

    @Test
    fun `阶梯解析正常格式`() {
        assertEquals(
            listOf(15 to 1, 10 to 2, 5 to 9),
            parseDespairLadder("15:1,10:2,5:9")
        )
    }

    @Test
    fun `阶梯解析容忍空白与非法片段`() {
        assertEquals(
            listOf(15 to 1, 10 to 2),
            parseDespairLadder(" 15 : 1 , junk , 10:2 , 0:3 , 4:0 , -1:2 , 3 ")
        )
    }

    @Test
    fun `阶梯解析空串`() {
        assertEquals(emptyList<Pair<Int, Int>>(), parseDespairLadder(""))
    }

    // ===== 评估器：布尔前置 × 血量阶梯 =====

    /** hero 血量 = health（无伤害/护甲），ableAtcSum = acceptableRivalAttack(resource)。 */
    private fun despairWarInfo(
        heroBlood: Int,
        meResource: Int,
        rivalAtcSum: Int,
        hand: List<ComboCard> = emptyList()
    ): WarInfo = createMockWarInfo(
        rivalPlayCards = if (rivalAtcSum > 0) listOf(createMockCard(atc = rivalAtcSum)) else emptyList(),
        handComboCards = hand,
        meHero = createMockCard(health = heroBlood),
        meResource = meResource
    )

    private val normalHand = listOf(buildCard("H1", cost = 2, powerWeight = 2.0))

    @Test
    fun `前置成立血量9命中两档取最大`() {
        // 我方无嘲讽 → excessDamage = 6；resource 3 → ableAtcSum = 1.5*3+1 = 5；6 ≥ 5 承压
        // 血 9 < 15（Δ1）且 < 10（Δ2）→ 取最大 2
        assertEquals(
            2,
            despairWarInfo(heroBlood = 9, meResource = 3, rivalAtcSum = 6, hand = normalHand)
                .surplusDespairNDelta(enabled = true, ladder = "15:1,10:2,5:9")
        )
    }

    @Test
    fun `血量14只命中第一档`() {
        assertEquals(
            1,
            despairWarInfo(heroBlood = 14, meResource = 3, rivalAtcSum = 6, hand = normalHand)
                .surplusDespairNDelta(enabled = true, ladder = "15:1,10:2,5:9")
        )
    }

    @Test
    fun `血量4全档命中取9`() {
        assertEquals(
            9,
            despairWarInfo(heroBlood = 4, meResource = 3, rivalAtcSum = 6, hand = normalHand)
                .surplusDespairNDelta(enabled = true, ladder = "15:1,10:2,5:9")
        )
    }

    @Test
    fun `血量15及以上不松门`() {
        assertEquals(
            0,
            despairWarInfo(heroBlood = 15, meResource = 3, rivalAtcSum = 6, hand = normalHand)
                .surplusDespairNDelta(enabled = true, ladder = "15:1,10:2,5:9")
        )
    }

    @Test
    fun `场面可容忍不绝望`() {
        // excessDamage 6 < ableAtcSum 12（resource 8）：场面压力在容忍度内，前置失败
        assertEquals(
            0,
            despairWarInfo(heroBlood = 9, meResource = 8, rivalAtcSum = 6, hand = normalHand)
                .surplusDespairNDelta(enabled = true, ladder = "15:1,10:2,5:9")
        )
    }

    @Test
    fun `空场无压力不绝望`() {
        // excessDamage = 0：对手没场面（或嘲讽全承），不存在场面承压，血再低也不因场面松门
        assertEquals(
            0,
            despairWarInfo(heroBlood = 4, meResource = 3, rivalAtcSum = 0, hand = normalHand)
                .surplusDespairNDelta(enabled = true, ladder = "15:1,10:2,5:9")
        )
    }

    @Test
    fun `手牌仍有惜售牌不绝望`() {
        // N>0 惜售牌在手（含不可负担）= 声明未来战术收益仍可能，捏牌有意义
        val hand = normalHand + buildCard("H5", cost = 8, powerWeight = 5.0, idleThreshold = 1)
        assertEquals(
            0,
            despairWarInfo(heroBlood = 4, meResource = 3, rivalAtcSum = 6, hand = hand)
                .surplusDespairNDelta(enabled = true, ladder = "15:1,10:2,5:9")
        )
    }

    @Test
    fun `未启用恒不松门`() {
        assertEquals(
            0,
            despairWarInfo(heroBlood = 4, meResource = 3, rivalAtcSum = 6, hand = normalHand)
                .surplusDespairNDelta(enabled = false, ladder = "15:1,10:2,5:9")
        )
    }
}
