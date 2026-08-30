package lin.domain.use.plan

import lin.bean.usePlan.DefaultPurposeTagIntentRuleProvider
import lin.bean.usePlan.PurposeTagId
import lin.bean.usePlan.UseStage
import org.junit.Assert.*
import org.junit.Test

/**
 * 生产侧用途标签规则表的**语义决策**回归网（Q-033 复核，2026-08-30）。
 *
 * 与 `UseIntentDeriverTest` 职责区分：后者测**推导逻辑**（用自定义 fixture），
 * 本类测**生产规则表的实际配置值**——防止有人无意改回「战术标签一律 N=1」的过度概括。
 */
class DefaultPurposeTagIntentRuleProviderTest {

    private val rules = DefaultPurposeTagIntentRuleProvider().rules()
        .associateBy { it.tagId }

    /**
     * N=1 只保留给「晚出可能更有价值」的标签。
     *
     * 背景：N 的真实语义是「留到后面可能更有价值」，这是**局面属性**，不普遍属于用途。
     * 原实现把「战术功能标签」一律置 N=1 属过度概括，且 N>0 会把牌挡在第一轮主组合外
     * （`passesFirstRoundCandidate()`：`ts != 0 || N == 0`），连带使其 `defaultStage` 排序空设。
     */
    @Test
    fun `惜售N只保留给保命与解场`() {
        // 晚出可能更有价值：血量可能更危险 / 可能出现更大威胁
        assertEquals(1, rules[PurposeTagId.SAVE_LIFE]?.defaultSurplusIdleThreshold)
        assertEquals(1, rules[PurposeTagId.CLEAN]?.defaultSurplusIdleThreshold)
    }

    @Test
    fun `成长牌不设惜售门槛`() {
        // 成长牌早出才有价值，晚出就废了；且 N>0 会让其 stage=SETUP 排序形同空设
        assertNull(rules[PurposeTagId.GREED]?.defaultSurplusIdleThreshold)
    }

    @Test
    fun `过牌牌不设惜售门槛`() {
        // 早过牌早赚，惜售与过牌目的相反
        assertNull(rules[PurposeTagId.DRAW_CARD]?.defaultSurplusIdleThreshold)
    }

    @Test
    fun `价值牌与额外费用牌不设惜售门槛`() {
        assertNull(rules[PurposeTagId.VALUE]?.defaultSurplusIdleThreshold)
        assertNull(rules[PurposeTagId.EXTRA_COST]?.defaultSurplusIdleThreshold)
    }

    /** FINISH 无条目：斩杀是局面属性，编排映射已取消（查询用途保留在 PurposeTagProvider）。 */
    @Test
    fun `FINISH无规则条目`() {
        assertNull("FINISH 不应有编排规则条目", rules[PurposeTagId.FINISH])
    }

    @Test
    fun `阶段映射保持稳定`() {
        assertEquals(UseStage.LATE, rules[PurposeTagId.SAVE_LIFE]?.defaultStage)
        assertEquals(UseStage.MID, rules[PurposeTagId.CLEAN]?.defaultStage)
        assertEquals(UseStage.SETUP, rules[PurposeTagId.GREED]?.defaultStage)
        assertEquals(UseStage.GENERAL, rules[PurposeTagId.VALUE]?.defaultStage)
        assertEquals(UseStage.GENERAL, rules[PurposeTagId.EXTRA_COST]?.defaultStage)
        // T-030（D-017）：过牌与解牌同段，靠 defaultOrderWeight 分先后
        assertEquals(UseStage.MID, rules[PurposeTagId.DRAW_CARD]?.defaultStage)
    }

    /** T-030（D-017）：defaultOrderWeight 首次启用——解牌先于过牌。 */
    @Test
    fun `解牌与过牌同段时解牌先出`() {
        val cleanW = rules[PurposeTagId.CLEAN]?.defaultOrderWeight ?: 0.0
        val drawW = rules[PurposeTagId.DRAW_CARD]?.defaultOrderWeight ?: 0.0
        assertTrue("解牌权重($cleanW) 应大于过牌权重($drawW)", cleanW > drawW)
        assertEquals(UseStage.MID, rules[PurposeTagId.CLEAN]?.defaultStage)
        assertEquals(UseStage.MID, rules[PurposeTagId.DRAW_CARD]?.defaultStage)
    }

    /** T-030 定标：CLEAN=1 / DRAW_CARD=0，防漂移。 */
    @Test
    fun `defaultOrderWeight定标不漂移`() {
        assertEquals(1.0, rules[PurposeTagId.CLEAN]?.defaultOrderWeight ?: 0.0, 0.0)
        assertEquals(0.0, rules[PurposeTagId.DRAW_CARD]?.defaultOrderWeight ?: 0.0, 0.0)
    }
}
