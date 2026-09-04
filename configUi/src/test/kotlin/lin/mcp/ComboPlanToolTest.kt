package lin.mcp

import lin.repository.combo_plan.ComboPlanDefinitionEntity
import lin.repository.combo_plan.ComboPlanDefinitionRepository
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.koin.core.context.GlobalContext

class ComboPlanToolTest : McpTestEnv() {

    private val testPlanId = "test_cp_101"
    private val testManagerId = "test_mgr_combo_1"
    private val repo: ComboPlanDefinitionRepository by lazy {
        GlobalContext.get().get()
    }

    @Before
    fun setupData() {
        // 插入测试用的 ComboPlan 记录
        repo.save(
            ComboPlanDefinitionEntity(
                managerId = testManagerId,
                id = testPlanId,
                coreGroupIds = "binding_core_1,binding_core_2",
                depGroupIds = "binding_dep_1",
                score = 3.5,
                // 起手协同加分（Q-009 / T-015）：只服务起手换牌，不影响出牌 score
                changeScore = 8.5,
                coreMutex = true,
                relation = "CORE_BEFORE_DEP",
                mustAdjacent = false
            )
        )
    }

    @After
    fun tearDownData() {
        // 清理测试用的 ComboPlan 记录
        repo.deleteById(testPlanId)
        cleanup()
    }

    @Test
    fun testComboPlanListAll() {
        val resp = call("list", """{"resource":"combo_plan"}""")
        assertFalse(resp.isError)
        val json = resp.contentJson
        assertTrue("list 应该包含测试 Plan ID", json.contains(testPlanId))
        assertTrue("list 应该包含关系 CORE_BEFORE_DEP", json.contains("CORE_BEFORE_DEP"))
    }

    @Test
    fun testComboPlanListByManagerId() {
        val resp = call("list", """{"resource":"combo_plan","managerId":"$testManagerId"}""")
        assertFalse(resp.isError)
        val json = resp.contentJson
        assertTrue(json.contains(testPlanId))

        val emptyResp = call("list", """{"resource":"combo_plan","managerId":"non_existent_mgr"}""")
        assertFalse(emptyResp.isError)
        assertEquals("[]", emptyResp.contentJson.trim())
    }

    @Test
    fun testComboPlanGetSuccess() {
        val resp = call("get", """{"resource":"combo_plan","id":"$testPlanId"}""")
        assertFalse(resp.isError)
        val json = resp.contentJson
        assertTrue("get 应输出 managerId", json.contains(testManagerId))
        assertTrue("get 应生成先手核心组步骤说明", json.contains("先手核心组"))
        assertTrue("get 应包含 sequence 出牌步骤", json.contains("sequence"))
    }

    @Test
    fun testChangeScoreRoundTrip() {
        // 存储往返：实体 → 库 → 实体 → 领域对象（起手侧消费的是 toDomain 后的 changeScore）
        val stored = repo.findById(testPlanId) ?: error("测试 Plan 未落库")
        assertEquals(8.5, stored.changeScore, 0.0)
        assertEquals(8.5, stored.toDomain().changeScore, 0.0)
        // 出牌侧的 score 未被起手字段污染
        assertEquals(3.5, stored.toDomain().score, 0.0)
    }

    @Test
    fun testChangeScoreEchoedInGetAndList() {
        val get = call("get", """{"resource":"combo_plan","id":"$testPlanId"}""")
        assertFalse(get.isError)
        assertTrue("get 应回显起手协同加分 changeScore", get.contentJson.contains("changeScore"))
        assertTrue("get 的 changeScore 值应为 8.5", get.contentJson.contains("8.5"))

        val list = call("list", """{"resource":"combo_plan"}""")
        assertFalse(list.isError)
        assertTrue("list 应回显 changeScore", list.contentJson.contains("changeScore"))
    }

    @Test
    fun testComboPlanGetNotFound() {
        val resp = call("get", """{"resource":"combo_plan","id":"invalid_id_999"}""")
        assertTrue("不存在的 ID 应返回 isError=true", resp.isError)
        assertTrue("错误提示应罗列现存的 Combo ID 列表防幻觉", resp.contentJson.contains(testPlanId))
    }

    @Test
    fun testUnknownResource() {
        val resp = call("list", """{"resource":"unknown_resource"}""")
        assertTrue("未知 resource 应返回 error", resp.isError)
        assertTrue("错误提示应说明支持的 resource 选项", resp.contentJson.contains("未知 resource"))
    }
}
