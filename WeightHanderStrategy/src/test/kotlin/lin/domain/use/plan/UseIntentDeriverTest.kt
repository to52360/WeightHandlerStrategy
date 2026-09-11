package lin.domain.use.plan

import lin.bean.usePlan.CardUseConfig
import lin.bean.usePlan.PurposeTagId
import lin.bean.usePlan.PurposeTagIntentRule
import lin.bean.usePlan.UseStage
import lin.serviceLoader.provider.PurposeTagIntentRuleProvider
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T-026：tag 默认余费门槛 N 推导测试——多标签命中的 defaultSurplusIdleThreshold 取 **max**
 * （有战术身份即惜售，保守方向；原 candidatePolicy 冲突软回落 NORMAL 语义被 max 覆盖）。
 * 显式 stageOverride 不阻断 N 推导（N 独立于阶段轴）。
 */
class UseIntentDeriverTest {

    private class TestRuleProvider(private val ruleList: List<PurposeTagIntentRule>) : PurposeTagIntentRuleProvider {
        override fun rules(): List<PurposeTagIntentRule> = ruleList
    }

    private val deriver = UseIntentDeriver(
        TestRuleProvider(
            listOf(
                PurposeTagIntentRule(
                    tagId = PurposeTagId.CLEAN,
                    defaultStage = UseStage.MID,
                    priority = 300,
                    defaultSurplusIdleThreshold = 1
                ),
                PurposeTagIntentRule(
                    tagId = PurposeTagId.GREED,
                    defaultStage = UseStage.SETUP,
                    priority = 100,
                    defaultSurplusIdleThreshold = 3
                ),
                // T-039：设非零 defaultOrderWeight，使「显式覆盖标签默认」与「stageOverride 不阻断推导」可被检验。
                // 注意：生产侧 7 条规则的 defaultOrderWeight 现仍全为 0.0——启用它们属 T-030，非本任务范围。
                PurposeTagIntentRule(
                    tagId = PurposeTagId.DRAW_CARD,
                    defaultStage = UseStage.GENERAL,
                    priority = 60,
                    defaultOrderWeight = 1.0,
                    defaultSurplusIdleThreshold = 1,
                    defaultReplanAfterUse = true
                ),
                PurposeTagIntentRule(
                    tagId = PurposeTagId.VALUE,
                    defaultStage = UseStage.GENERAL,
                    priority = 50,
                    defaultSurplusIdleThreshold = null
                ),
                // EXTRA_COST 未声明 defaultSurplusIdleThreshold
                PurposeTagIntentRule(
                    tagId = PurposeTagId.EXTRA_COST,
                    defaultStage = UseStage.GENERAL,
                    priority = 50
                )
            )
        )
    )

    @Test
    fun `无标签无显式覆盖推导为null`() {
        assertEquals(null, deriver.derive(CardUseConfig()).tagDefaultSurplusIdleThreshold)
    }

    @Test
    fun `未声明defaultSurplusIdleThreshold的标签回落null`() {
        val intent = deriver.derive(CardUseConfig(purposeTags = setOf(PurposeTagId.EXTRA_COST)))
        assertEquals(null, intent.tagDefaultSurplusIdleThreshold)
    }

    @Test
    fun `单标签默认余费门槛继承`() {
        val intent = deriver.derive(CardUseConfig(purposeTags = setOf(PurposeTagId.CLEAN)))
        assertEquals(1, intent.tagDefaultSurplusIdleThreshold)
    }

    @Test
    fun `多标签声明相同N不冲突`() {
        val intent = deriver.derive(CardUseConfig(purposeTags = setOf(PurposeTagId.CLEAN, PurposeTagId.DRAW_CARD)))
        assertEquals(1, intent.tagDefaultSurplusIdleThreshold)
    }

    @Test
    fun `多标签不同N取max保守惜售`() {
        // CLEAN(N=1) + GREED(N=3)：任一标签声明更高惜售即整体取最高——原冲突回落 NORMAL 语义被 max 替代
        val intent = deriver.derive(CardUseConfig(purposeTags = setOf(PurposeTagId.CLEAN, PurposeTagId.GREED)))
        assertEquals(3, intent.tagDefaultSurplusIdleThreshold)
    }

    @Test
    fun `null不参与max`() {
        // VALUE(N=null) + CLEAN(N=1)：null 视为未声明，不拉低惜售
        val intent = deriver.derive(CardUseConfig(purposeTags = setOf(PurposeTagId.VALUE, PurposeTagId.CLEAN)))
        assertEquals(1, intent.tagDefaultSurplusIdleThreshold)
    }

    @Test
    fun `显式stageOverride不阻断tag默认N推导`() {
        val intent = deriver.derive(
            CardUseConfig(
                purposeTags = setOf(PurposeTagId.CLEAN),
                stageOverride = UseStage.SETUP
            )
        )
        assertEquals(UseStage.SETUP, intent.stage)
        assertEquals(1, intent.tagDefaultSurplusIdleThreshold)
    }

    // ==================== T-036：tag 全局兜底 replanAfterUse ====================

    @Test
    fun `未声明replan的标签推导为false`() {
        // CLEAN 未配 defaultReplanAfterUse → 全局兜底不生效
        val intent = deriver.derive(CardUseConfig(purposeTags = setOf(PurposeTagId.CLEAN)))
        assertEquals(false, intent.tagDefaultReplanAfterUse)
        assertEquals(false, intent.replanAfterUse)
    }

    @Test
    fun `声明replan的标签全局兜底生效`() {
        // DRAW_CARD 声明 true → 打过牌的卡无需逐卡配置即自动重评估
        val intent = deriver.derive(CardUseConfig(purposeTags = setOf(PurposeTagId.DRAW_CARD)))
        assertEquals(true, intent.tagDefaultReplanAfterUse)
        assertEquals(true, intent.replanAfterUse)
    }

    @Test
    fun `多标签replan取any保守`() {
        // DRAW_CARD(true) + CLEAN(false)：任一标签要求重评估即重评估（与 N 的 max 同为保守方向）
        val intent = deriver.derive(CardUseConfig(purposeTags = setOf(PurposeTagId.DRAW_CARD, PurposeTagId.CLEAN)))
        assertEquals(true, intent.replanAfterUse)
    }

    @Test
    fun `卡级显式false不否决标签兜底replan`() {
        // OR 合并：卡级/分组显式 false + 标签 true → 仍 replan。
        // 取舍：漏评估会让后续牌按过时战场信息决策（清场场景），代价远大于多评估一次。
        val intent = deriver.derive(
            CardUseConfig(purposeTags = setOf(PurposeTagId.DRAW_CARD), replanAfterUse = false)
        )
        assertEquals(true, intent.replanAfterUse)
    }

    @Test
    fun `显式stageOverride不阻断tag兜底replan推导`() {
        // replan 属执行生命周期轴，与阶段轴正交（同 N 的既有约定）
        val intent = deriver.derive(
            CardUseConfig(purposeTags = setOf(PurposeTagId.DRAW_CARD), stageOverride = UseStage.SETUP)
        )
        assertEquals(UseStage.SETUP, intent.stage)
        assertEquals(true, intent.replanAfterUse)
    }

    // ==================== T-039：orderWeight 逐字段独立解析 ====================
    // 修复前：①stageOverride 非空时提前 return，defaultOrderWeight 被一并跳过；
    //        ②orderWeight == 0.0 当哨兵 → 无法显式配 0 覆盖标签默认的 1。

    @Test
    fun `显式stageOverride不阻断defaultOrderWeight推导`() {
        // 修复点①：阶段被覆盖时，标签的排序权重仍应生效（阶段轴 ⊥ 排序权重轴，同 N / replan）
        val intent = deriver.derive(
            CardUseConfig(purposeTags = setOf(PurposeTagId.DRAW_CARD), stageOverride = UseStage.SETUP)
        )
        assertEquals(UseStage.SETUP, intent.stage)
        assertEquals(1.0, intent.orderWeight, 0.0)
    }

    @Test
    fun `显式orderWeight优先于标签默认`() {
        // 显式 -1 覆盖 DRAW_CARD 标签默认的 1 → 排在同段末尾
        val intent = deriver.derive(CardUseConfig(purposeTags = setOf(PurposeTagId.DRAW_CARD), orderWeight = -1.0))
        assertEquals(-1.0, intent.orderWeight, 0.0)
    }

    @Test
    fun `显式配0可覆盖标签默认1`() {
        // 修复点②：这是原 0.0 哨兵做不到的——显式 0 表示「明确不要标签的默认 1」
        val intent = deriver.derive(CardUseConfig(purposeTags = setOf(PurposeTagId.DRAW_CARD), orderWeight = 0.0))
        assertEquals(0.0, intent.orderWeight, 0.0)
    }

    @Test
    fun `未配orderWeight回落0而不是标签默认值`() {
        // EXTRA_COST 未设 defaultOrderWeight(null→0.0)，无显式配置 → 0.0
        val intent = deriver.derive(CardUseConfig(purposeTags = setOf(PurposeTagId.EXTRA_COST)))
        assertEquals(0.0, intent.orderWeight, 0.0)
    }

    @Test
    fun `无标签时显式orderWeight仍生效`() {
        val intent = deriver.derive(CardUseConfig(orderWeight = 5.0))
        assertEquals(UseStage.GENERAL, intent.stage)
        assertEquals(5.0, intent.orderWeight, 0.0)
    }

    @Test
    fun `stageOverride与显式orderWeight可组合`() {
        // 三分支合一后：阶段取显式、权重取显式，互不干扰
        val intent = deriver.derive(
            CardUseConfig(purposeTags = setOf(PurposeTagId.DRAW_CARD), stageOverride = UseStage.MID, orderWeight = 2.0)
        )
        assertEquals(UseStage.MID, intent.stage)
        assertEquals(2.0, intent.orderWeight, 0.0)
    }
}
