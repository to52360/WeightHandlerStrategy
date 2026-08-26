package lin.domain.use.plan

import lin.bean.usePlan.*
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
                    defaultStage = UseStage.CLEAR,
                    priority = 300,
                    defaultSurplusIdleThreshold = 1
                ),
                PurposeTagIntentRule(
                    tagId = PurposeTagId.GREED,
                    defaultStage = UseStage.SETUP,
                    priority = 100,
                    defaultSurplusIdleThreshold = 3
                ),
                PurposeTagIntentRule(
                    tagId = PurposeTagId.DRAW_CARD,
                    defaultStage = UseStage.GENERAL,
                    priority = 60,
                    defaultSurplusIdleThreshold = 1
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
}
