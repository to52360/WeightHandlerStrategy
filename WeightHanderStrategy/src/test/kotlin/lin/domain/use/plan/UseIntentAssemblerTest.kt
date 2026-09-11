package lin.domain.use.plan

import lin.bean.usePlan.*
import lin.serviceLoader.provider.PurposeTagIntentRuleProvider
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T-026：UseIntentAssembler 装配测试——tag 默认余费门槛 N 由用途标签推导；
 * GroupUseOverride（T-026 后无 candidatePolicy 字段）只影响 stage/replanAfterUse/orderWeight，不触碰 N。
 */
class UseIntentAssemblerTest {

    private class TagRuleProvider(private val ruleList: List<PurposeTagIntentRule>) : PurposeTagIntentRuleProvider {
        override fun rules(): List<PurposeTagIntentRule> = ruleList
    }

    /** CLEAN 标签默认 N=1（惜售声明） */
    private val tagDeriver = UseIntentDeriver(
        TagRuleProvider(
            listOf(
                PurposeTagIntentRule(
                    tagId = PurposeTagId.CLEAN,
                    defaultStage = UseStage.MID,
                    priority = 300,
                    defaultSurplusIdleThreshold = 1
                )
            )
        )
    )

    @Test
    fun `标签默认余费门槛生效`() {
        val asm = UseIntentAssembler(
            cardPurposes = mapOf("c1" to CardPurpose(purposeTags = setOf(PurposeTagId.CLEAN))),
            groupMap = emptyMap(),
            groupOverrides = emptyMap(),
            deriver = tagDeriver
        )
        assertEquals(1, asm.assemble("c1").tagDefaultSurplusIdleThreshold)
        assertEquals(UseStage.MID, asm.assemble("c1").stage)
    }

    @Test
    fun `分组覆盖改阶段但N仍由tag推导`() {
        val asm = UseIntentAssembler(
            cardPurposes = mapOf("c1" to CardPurpose(purposeTags = setOf(PurposeTagId.CLEAN))),
            groupMap = mapOf("c1" to setOf("g1")),
            groupOverrides = mapOf("g1" to GroupUseOverride(stageOverride = UseStage.SETUP)),
            deriver = tagDeriver
        )
        val intent = asm.assemble("c1")
        assertEquals(UseStage.SETUP, intent.stage)
        assertEquals(1, intent.tagDefaultSurplusIdleThreshold)
    }

    @Test
    fun `无标签无覆盖回落null与默认阶段`() {
        val asm = UseIntentAssembler(
            cardPurposes = emptyMap(),
            groupMap = emptyMap(),
            groupOverrides = emptyMap(),
            deriver = tagDeriver
        )
        val intent = asm.assemble("c1")
        assertEquals(null, intent.tagDefaultSurplusIdleThreshold)
        assertEquals(UseStage.GENERAL, intent.stage)
    }
}
