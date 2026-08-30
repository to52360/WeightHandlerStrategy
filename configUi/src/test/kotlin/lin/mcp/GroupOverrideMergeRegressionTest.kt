package lin.mcp

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
 * T-026 回归：group_override SET 分支基于 existingOverride.copy 合并（未暴露字段 replanAfterUse/orderWeight 不丢）。
 *
 * 原 GroupOverrideCandidatePolicyTest（T-023）随 CandidatePolicy 全链退役改写——candidatePolicy 相关断言移除，
 * 保留 T-023 修复的「SET 分支重建 GroupUseOverride 丢未暴露字段」回归锚点。
 */
class GroupOverrideMergeRegressionTest : McpTestEnv() {

    /** 建独立 fixture（manager + 单 binding），@After 由 McpTestEnv.cleanup 按 managerId 级联清理 */
    private fun setupFixture(name: String): Pair<String, String> {
        val binding = CardGroupBinding(
            id = "t026_${name}",
            managerId = "",
            name = "组_$name",
            cardIds = emptyList()
        )
        managerId = GlobalContext.get().get<CardGroupService>().saveManager(
            name = "t026_fixture_$name",
            sourceFile = "t026_fixture_$name",
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
    fun `SET 只改 stageOverride 时未暴露字段 replanAfterUse orderWeight 保留`() {
        val (mid, bid) = setupFixture("keep")
        val service = GlobalContext.get().get<CardGroupService>()

        // 1. MCP SET stageOverride
        call("group_override", """{"bindingId":"$bid","stageOverride":"SETUP"}""")

        // 2. 经 service 通道补 MCP 未暴露字段（模拟 UI 写入 replanAfterUse/orderWeight）
        val binding = service.loadBindings(mid).first { it.id == bid }
        val withHidden = binding.behaviors.map { b ->
            if (b is CardGroupBehavior.OverrideBehavior)
                CardGroupBehavior.OverrideBehavior(b.override.copy(replanAfterUse = true, orderWeight = 2.5))
            else b
        }
        service.saveBinding(binding.copy(behaviors = withHidden))

        // 3. MCP 只改 stageOverride → replanAfterUse/orderWeight 必须保留（修复前重建 GroupUseOverride 会丢）
        val resp = call("group_override", """{"bindingId":"$bid","stageOverride":"MID"}""")
        assertFalse(resp.isError)
        val after = overrideOf(mid, bid)
        assertNotNull(after)
        assertEquals(UseStage.MID, after!!.stageOverride)
        assertEquals(true, after.replanAfterUse)
        assertEquals(2.5, after.orderWeight!!, 1e-9)
    }

    @Test
    fun `clear 返回 previousOverride 含可恢复字段`() {
        val (_, bid) = setupFixture("clear")
        call("group_override", """{"bindingId":"$bid","stageOverride":"SETUP"}""")
        val resp = call("group_override", """{"bindingId":"$bid","clearOverride":true}""")
        assertFalse(resp.isError)
        val json = mapper.readTree(resp.contentJson)
        assertEquals("SETUP", json.get("previousOverride")?.get("stageOverride")?.asText())
    }
}
