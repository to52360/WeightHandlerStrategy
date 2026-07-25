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
        val resp = call("combo_plan", """{"action":"LIST"}""")
        assertFalse(resp.isError)
        val json = resp.contentJson
        assertTrue("LIST 应该包含测试 Plan ID", json.contains(testPlanId))
        assertTrue("LIST 应该包含关系 CORE_BEFORE_DEP", json.contains("CORE_BEFORE_DEP"))
    }

    @Test
    fun testComboPlanListByManagerId() {
        val resp = call("combo_plan", """{"action":"LIST","managerId":"$testManagerId"}""")
        assertFalse(resp.isError)
        val json = resp.contentJson
        assertTrue(json.contains(testPlanId))

        val emptyResp = call("combo_plan", """{"action":"LIST","managerId":"non_existent_mgr"}""")
        assertFalse(emptyResp.isError)
        assertEquals("[]", emptyResp.contentJson.trim())
    }

    @Test
    fun testComboPlanGetSuccess() {
        val resp = call("combo_plan", """{"action":"GET","id":"$testPlanId"}""")
        assertFalse(resp.isError)
        val json = resp.contentJson
        assertTrue("GET 应输出 managerId", json.contains(testManagerId))
        assertTrue("GET 应生成先手核心组步骤说明", json.contains("先手核心组"))
        assertTrue("GET 应包含 sequence 出牌步骤", json.contains("sequence"))
    }

    @Test
    fun testComboPlanGetNotFound() {
        val resp = call("combo_plan", """{"action":"GET","id":"invalid_id_999"}""")
        assertTrue("不存在的 ID 应返回 isError=true", resp.isError)
        assertTrue("错误提示应罗列现存的 Combo ID 列表防幻觉", resp.contentJson.contains(testPlanId))
    }

    @Test
    fun testInvalidAction() {
        val resp = call("combo_plan", """{"action":"UNKNOWN"}""")
        assertTrue("未知 action 应返回 error", resp.isError)
        assertTrue("错误提示应说明支持的 action 选项", resp.contentJson.contains("支持 LIST / GET"))
    }
}
