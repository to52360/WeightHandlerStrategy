package lin.mcp

import com.fasterxml.jackson.databind.JsonNode
import lin.bean.usePlan.CandidatePolicy
import lin.bean.usePlan.GroupUseOverride
import lin.bean.usePlan.UseStage
import lin.repository.card_group.CardGroupService
import lin.rule.tree.CardGroupBehavior
import lin.rule.tree.CardGroupBinding
import lin.rule.tree.findOverride
import org.junit.Assert.*
import org.junit.Test
import org.koin.core.context.GlobalContext

/**
 * T-023：group_override 支持 candidatePolicy 组级候选策略配置（GroupUseOverride.candidatePolicy 补 MCP 入口）。
 *
 * 验证：①SET candidatePolicy 落库回读；②缺省保留原值且未暴露字段（replanAfterUse/orderWeight）不丢
 * （回归 SET 分支重建 GroupUseOverride 丢字段缺陷）；③非法值拒绝；④clear 的 previousOverride 含 candidatePolicy。
 */
class GroupOverrideCandidatePolicyTest : McpTestEnv() {

    /** 建独立 fixture（manager + 单 binding），@After 由 McpTestEnv.cleanup 按 managerId 级联清理 */
    private fun setupFixture(name: String): Pair<String, String> {
        val binding = CardGroupBinding(
            id = "t023_${name}",
            managerId = "",
            name = "组_$name",
            cardIds = emptyList()
        )
        managerId = GlobalContext.get().get<CardGroupService>().saveManager(
            name = "t023_fixture_$name",
            sourceFile = "t023_fixture_$name",
            enabled = true,
            bindings = listOf(binding)
        )
        return managerId!! to binding.id
    }

    private fun overrideOf(managerId: String, bindingId: String): GroupUseOverride? =
        GlobalContext.get().get<CardGroupService>()
            .loadBindings(managerId).firstOrNull { it.id == bindingId }
            ?.behaviors?.findOverride()

    @Test
    fun `SET candidatePolicy 落库并可回读`() {
        val (mid, bid) = setupFixture("set")
        val resp = call("group_override", """{"bindingId":"$bid","candidatePolicy":"SURPLUS_ONLY"}""")
        assertFalse(resp.isError)
        assertEquals(CandidatePolicy.SURPLUS_ONLY, overrideOf(mid, bid)?.candidatePolicy)
    }

    @Test
    fun `缺省保留原值且未暴露字段不丢`() {
        val (mid, bid) = setupFixture("keep")
        val service = GlobalContext.get().get<CardGroupService>()

        // 1. MCP SET stageOverride + candidatePolicy
        call("group_override", """{"bindingId":"$bid","stageOverride":"SETUP","candidatePolicy":"SURPLUS_ONLY"}""")

        // 2. 经 service 通道补一个 MCP 未暴露的字段（模拟 UI 写入 replanAfterUse）
        val binding = service.loadBindings(mid).first { it.id == bid }
        val withReplan = binding.behaviors.map { b ->
            if (b is CardGroupBehavior.OverrideBehavior)
                CardGroupBehavior.OverrideBehavior(b.override.copy(replanAfterUse = true))
            else b
        }
        service.saveBinding(binding.copy(behaviors = withReplan))

        // 3. MCP 只改 stageOverride → candidatePolicy 与 replanAfterUse 必须保留（修复前重建 GroupUseOverride 会丢）
        val resp = call("group_override", """{"bindingId":"$bid","stageOverride":"CLEAR"}""")
        assertFalse(resp.isError)
        val after = overrideOf(mid, bid)
        assertNotNull(after)
        assertEquals(UseStage.CLEAR, after!!.stageOverride)
        assertEquals(CandidatePolicy.SURPLUS_ONLY, after.candidatePolicy)
        assertEquals(true, after.replanAfterUse)
    }

    @Test
    fun `非法 candidatePolicy 拒绝`() {
        val (_, bid) = setupFixture("bad")
        val resp = call("group_override", """{"bindingId":"$bid","candidatePolicy":"NOT_A_POLICY"}""")
        assertTrue(resp.isError)
    }

    @Test
    fun `clear 返回 previousOverride 含 candidatePolicy`() {
        val (_, bid) = setupFixture("clear")
        call("group_override", """{"bindingId":"$bid","candidatePolicy":"TACTICS_DOMINANT"}""")
        val resp = call("group_override", """{"bindingId":"$bid","clearOverride":true}""")
        assertFalse(resp.isError)
        val json: JsonNode = mapper.readTree(resp.contentJson)
        assertEquals("TACTICS_DOMINANT", json.get("previousOverride")?.get("candidatePolicy")?.asText())
    }
}
