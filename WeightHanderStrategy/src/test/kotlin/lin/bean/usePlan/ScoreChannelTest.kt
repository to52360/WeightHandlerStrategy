package lin.bean.usePlan

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Q-008：defaultCandidatePolicy 自动推导 defaultChannel 的映射测试。
 * TACTICS_DOMINANT → TACTICAL；NORMAL / SURPLUS_ONLY / null → GENERAL。
 */
class ScoreChannelTest {

    @Test
    fun `战术主导推导为战术通道`() {
        assertEquals(ScoreChannel.TACTICAL, ScoreChannel.fromCandidatePolicy(CandidatePolicy.TACTICS_DOMINANT))
    }

    @Test
    fun `普通牌推导为普通通道`() {
        assertEquals(ScoreChannel.GENERAL, ScoreChannel.fromCandidatePolicy(CandidatePolicy.NORMAL))
    }

    @Test
    fun `余费专用推导为普通通道`() {
        assertEquals(ScoreChannel.GENERAL, ScoreChannel.fromCandidatePolicy(CandidatePolicy.SURPLUS_ONLY))
    }

    @Test
    fun `未声明候选策略回落普通通道`() {
        assertEquals(ScoreChannel.GENERAL, ScoreChannel.fromCandidatePolicy(null))
    }
}
