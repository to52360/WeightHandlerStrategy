package lin.domain.use.plan

import lin.bean.usePlan.*
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T-006：candidatePolicy 推导测试——显式覆盖 > 唯一标签默认 > NORMAL；冲突不按 priority 裁决。
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
                    defaultCandidatePolicy = CandidatePolicy.TACTICS_DOMINANT
                ),
                PurposeTagIntentRule(
                    tagId = PurposeTagId.DRAW_CARD,
                    defaultStage = UseStage.GENERAL,
                    priority = 60,
                    defaultCandidatePolicy = CandidatePolicy.TACTICS_DOMINANT
                ),
                PurposeTagIntentRule(
                    tagId = PurposeTagId.VALUE,
                    defaultStage = UseStage.GENERAL,
                    priority = 50,
                    defaultCandidatePolicy = CandidatePolicy.NORMAL
                ),
                // EXTRA_COST 未声明 defaultCandidatePolicy
                PurposeTagIntentRule(
                    tagId = PurposeTagId.EXTRA_COST,
                    defaultStage = UseStage.GENERAL,
                    priority = 50
                )
            )
        )
    )

    @Test
    fun `无标签无显式覆盖推导为NORMAL`() {
        assertEquals(CandidatePolicy.NORMAL, deriver.derive(CardUseConfig()).candidatePolicy)
    }

    @Test
    fun `未声明defaultCandidatePolicy的标签回落NORMAL`() {
        val intent = deriver.derive(CardUseConfig(purposeTags = setOf(PurposeTagId.EXTRA_COST)))
        assertEquals(CandidatePolicy.NORMAL, intent.candidatePolicy)
    }

    @Test
    fun `单标签默认候选策略继承`() {
        val intent = deriver.derive(CardUseConfig(purposeTags = setOf(PurposeTagId.CLEAN)))
        assertEquals(CandidatePolicy.TACTICS_DOMINANT, intent.candidatePolicy)
    }

    @Test
    fun `多标签声明相同策略不冲突`() {
        val intent = deriver.derive(CardUseConfig(purposeTags = setOf(PurposeTagId.CLEAN, PurposeTagId.DRAW_CARD)))
        assertEquals(CandidatePolicy.TACTICS_DOMINANT, intent.candidatePolicy)
    }

    @Test
    fun `显式覆盖优先于标签默认`() {
        val intent = deriver.derive(
            CardUseConfig(
                purposeTags = setOf(PurposeTagId.CLEAN),
                candidatePolicy = CandidatePolicy.SURPLUS_ONLY
            )
        )
        assertEquals(CandidatePolicy.SURPLUS_ONLY, intent.candidatePolicy)
    }

    @Test(expected = IllegalStateException::class)
    fun `多标签声明冲突策略不按priority裁决直接抛异常`() {
        // CLEAN → TACTICS_DOMINANT(300)，VALUE → NORMAL(50)：priority 更高的是 CLEAN，但冲突必须显式覆盖
        deriver.derive(CardUseConfig(purposeTags = setOf(PurposeTagId.CLEAN, PurposeTagId.VALUE)))
    }
}
