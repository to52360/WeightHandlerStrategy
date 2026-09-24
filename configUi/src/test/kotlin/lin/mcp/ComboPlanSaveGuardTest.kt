package lin.mcp

import lin.repository.combo_plan.ComboPlanDefinitionEntity
import lin.repository.combo_plan.ComboPlanDefinitionRepository
import lin.repository.combo_plan.ComboPlanProblem
import lin.repository.combo_plan.ComboPlanSaveResult
import lin.repository.combo_plan.ComboPlanService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.koin.core.context.GlobalContext
import org.springframework.jdbc.core.JdbcTemplate

/**
 * D-DC-007（`T-DC-012`）：Combo 写入校验单点 —— UI 与 MCP 两条通道共用 `ComboPlanService.save`。
 *
 * 覆盖：①合法保存落库且短 id 由单点分配 ②跨卡组分组 id 被拒且不落库 ③核心组空被拒 ④依赖组空被拒
 * ⑤目标卡组不存在被拒 ⑥relation 非法被拒 ⑦MCP 通道走同一判定（文案仍带候选列表）。
 *
 * 环境（Koin / DB / 工具注册 / 清理）复用 [McpTestEnv]；本类只写断言。
 * 卡组与分组直接落表（与 `ComboPlanManagementTest` 同款），不经卡池校验 ⇒ 无卡池文件依赖。
 */
class ComboPlanSaveGuardTest : McpTestEnv() {

    private val managerA = "dc012_mgr_a"
    private val managerNameA = "dc012_guard_groups_a"
    private val bindingCoreA = "dc012_a_core"
    private val bindingDepA = "dc012_a_dep"

    private val managerB = "dc012_mgr_b"
    private val managerNameB = "dc012_guard_groups_b"
    private val bindingCoreB = "dc012_b_core"
    private val bindingDepB = "dc012_b_dep"

    private val jdbc: JdbcTemplate by lazy { GlobalContext.get().get() }
    private val service: ComboPlanService by lazy { GlobalContext.get().get() }
    private val repository: ComboPlanDefinitionRepository by lazy { GlobalContext.get().get() }

    @Before
    fun prepareTwoManagers() {
        cleanupAll("dc012_guard_a", managerNameA)
        cleanupAll("dc012_guard_b", managerNameB)
        insertManager(managerA, managerNameA, bindingCoreA, bindingDepA)
        insertManager(managerB, managerNameB, bindingCoreB, bindingDepB)
        // 基类 cleanup 级联清理 A（含其 combo 行）；B 由本类 @After 清理
        managerId = managerA
    }

    private fun insertManager(mgrId: String, name: String, coreId: String, depId: String) {
        jdbc.update(
            "INSERT INTO card_group_manager (id, name, source_file, enabled) VALUES (?, ?, ?, ?)",
            mgrId, name, "dc012_guard.cardgroup", 0
        )
        jdbc.update(
            "INSERT INTO card_group_binding (id, manager_id, name, card_ids) VALUES (?, ?, ?, ?)",
            coreId, mgrId, "核心组", "[\"ICC_820\"]"
        )
        jdbc.update(
            "INSERT INTO card_group_binding (id, manager_id, name, card_ids) VALUES (?, ?, ?, ?)",
            depId, mgrId, "依赖组", "[\"ICC_820\"]"
        )
    }

    @Test
    fun `合法保存经单点落库且短 id 由单点分配`() {
        val result = service.save(entity(managerA, id = "", core = bindingCoreA, dep = bindingDepA))

        assertTrue("合法保存应成功: $result", result is ComboPlanSaveResult.Saved)
        val saved = result as ComboPlanSaveResult.Saved
        assertTrue("id 留空时短 id 应由单点分配", saved.id.isNotBlank())
        assertEquals("应回传所属卡组名", managerNameA, saved.managerName)
        assertNotNull("应真正落库", repository.findById(saved.id))
    }

    @Test
    fun `跨卡组分组被拒且不落库`() {
        val rejectedId = "dc012_foreign_reject"
        val result = service.save(entity(managerA, rejectedId, core = bindingCoreA, dep = bindingDepB))

        assertTrue("跨卡组分组应被拒: $result", result is ComboPlanSaveResult.Rejected)
        val problems = (result as ComboPlanSaveResult.Rejected).problems
        assertTrue("应识别为跨卡组分组: $problems", problems.any { it is ComboPlanProblem.ForeignGroups })
        assertNull("被拒不应落库", repository.findById(rejectedId))
    }

    @Test
    fun `核心组与依赖组各自为空都被拒`() {
        val noCore = service.save(entity(managerA, id = "", core = "", dep = bindingDepA))
        assertTrue(noCore is ComboPlanSaveResult.Rejected)
        assertTrue(
            "核心组空应被拒: $noCore",
            (noCore as ComboPlanSaveResult.Rejected).problems.any { it is ComboPlanProblem.EmptyCore }
        )

        val noDep = service.save(entity(managerA, id = "", core = bindingCoreA, dep = ""))
        assertTrue(noDep is ComboPlanSaveResult.Rejected)
        assertTrue(
            "依赖组空应被拒: $noDep",
            (noDep as ComboPlanSaveResult.Rejected).problems.any { it is ComboPlanProblem.EmptyDep }
        )
    }

    @Test
    fun `目标卡组不存在被拒`() {
        val result = service.save(entity("dc012_no_such_manager", "", bindingCoreA, bindingDepA))

        assertTrue(result is ComboPlanSaveResult.Rejected)
        assertTrue(
            "应识别为卡组不存在: $result",
            (result as ComboPlanSaveResult.Rejected).problems.any { it is ComboPlanProblem.ManagerNotFound }
        )
    }

    @Test
    fun `非法 relation 被拒`() {
        val result = service.save(
            entity(managerA, "", bindingCoreA, bindingDepA, relation = "NO_SUCH_RELATION")
        )

        assertTrue(result is ComboPlanSaveResult.Rejected)
        assertTrue(
            "应识别为非法 relation: $result",
            (result as ComboPlanSaveResult.Rejected).problems.any { it is ComboPlanProblem.InvalidRelation }
        )
    }

    /** 同一判定走 MCP 通道：被拒且文案仍带「不属于卡组」与有效分组候选。 */
    @Test
    fun `MCP 通道走同一判定`() {
        val resp = call(
            "save_combo_plan",
            """{"managerId":"$managerA","coreGroupIds":["$bindingCoreA"],"depGroupIds":["$bindingDepB"]}"""
        )
        assertTrue("跨卡组应报错: ${resp.contentJson}", resp.isError)
        assertTrue("错误文案应说明不属于卡组: ${resp.contentJson}", resp.contentJson.contains("不属于卡组"))
        assertTrue("错误文案仍应给出有效分组候选: ${resp.contentJson}", resp.contentJson.contains("有效分组绑定列表"))
    }

    private fun entity(
        managerId: String,
        id: String,
        core: String,
        dep: String,
        relation: String = "SCORE_ONLY"
    ) = ComboPlanDefinitionEntity(
        managerId = managerId,
        id = id,
        coreGroupIds = core,
        depGroupIds = dep,
        score = 1.0,
        changeScore = 0.0,
        coreMutex = true,
        relation = relation,
        mustAdjacent = false
    )

    @After
    fun deleteSecondManager() {
        runCatching {
            jdbc.update("DELETE FROM combo_plan_definition WHERE manager_id = ?", managerB)
            jdbc.update("DELETE FROM card_group_binding WHERE manager_id = ?", managerB)
            jdbc.update("DELETE FROM card_group_manager WHERE id = ?", managerB)
        }
    }
}
