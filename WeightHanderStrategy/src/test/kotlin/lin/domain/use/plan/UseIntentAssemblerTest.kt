package lin.domain.use.plan

import lin.bean.usePlan.*
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T-006：UseIntentAssembler 三层覆盖测试——GroupUseOverride > CardPurpose > 用途标签默认。
 */
class UseIntentAssemblerTest {

    private class TagRuleProvider(private val ruleList: List<PurposeTagIntentRule>) : PurposeTagIntentRuleProvider {
        override fun rules(): List<PurposeTagIntentRule> = ruleList
    }

    /** CLEAN 标签默认 TACTICS_DOMINANT */
    private val tagDeriver = UseIntentDeriver(
        TagRuleProvider(
            listOf(
                PurposeTagIntentRule(
                    tagId = PurposeTagId.CLEAN,
                    defaultStage = UseStage.CLEAR,
                    priority = 300,
                    defaultCandidatePolicy = CandidatePolicy.TACTICS_DOMINANT
                )
            )
        )
    )

    @Test
    fun `标签默认候选策略生效`() {
        val asm = UseIntentAssembler(
            cardPurposes = mapOf("c1" to CardPurpose(purposeTags = setOf(PurposeTagId.CLEAN))),
            groupMap = emptyMap(),
            groupOverrides = emptyMap(),
            deriver = tagDeriver
        )
        assertEquals(CandidatePolicy.TACTICS_DOMINANT, asm.assemble("c1").candidatePolicy)
    }

    @Test
    fun `单卡显式覆盖优先于标签默认`() {
        val asm = UseIntentAssembler(
            cardPurposes = mapOf(
                "c1" to CardPurpose(
                    purposeTags = setOf(PurposeTagId.CLEAN),
                    candidatePolicy = CandidatePolicy.SURPLUS_ONLY
                )
            ),
            groupMap = emptyMap(),
            groupOverrides = emptyMap(),
            deriver = tagDeriver
        )
        assertEquals(CandidatePolicy.SURPLUS_ONLY, asm.assemble("c1").candidatePolicy)
    }

    @Test
    fun `分组覆盖优先于单卡`() {
        val asm = UseIntentAssembler(
            cardPurposes = mapOf(
                "c1" to CardPurpose(
                    purposeTags = setOf(PurposeTagId.CLEAN),
                    candidatePolicy = CandidatePolicy.SURPLUS_ONLY
                )
            ),
            groupMap = mapOf("c1" to setOf("g1")),
            groupOverrides = mapOf("g1" to GroupUseOverride(candidatePolicy = CandidatePolicy.TACTICS_DOMINANT)),
            deriver = tagDeriver
        )
        assertEquals(CandidatePolicy.TACTICS_DOMINANT, asm.assemble("c1").candidatePolicy)
    }
}
