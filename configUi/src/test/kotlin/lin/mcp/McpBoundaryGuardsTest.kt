package lin.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path
import java.nio.file.Paths

/**
 * MCP 边界守卫回归测试（2026-09-05，sop-rework）。
 *
 * - **T-007**：`tool_capabilities`（不传 resource）应回显 `databasePath` / `cwd`——
 *   定库凭证，替代"写后再查"与"靠 description 猜是哪个库"这两种脆弱手段。
 * - **T-009**：`save_card_group` 的 `existingId` 必须已存在，否则报错并列出可用方案——
 *   防止"续接引用上一轮旧 id → 静默分裂 / 新建 → 在原卡组读不到数据"。
 *
 * 环境（Koin / DB / 工具注册 / 清理）复用 [McpTestEnv]；本类只写断言。
 */
class McpBoundaryGuardsTest : McpTestEnv() {

    private val deckCode =
        "AAEBAZ8FBPfQArjFBdaABtK5Bg3YxwKd7ALZ/gL9uAPruQPTvQTi0wSZjgb1lQbt3waS4Aac6Aaf6AYAAA=="
    private val groupName = "t007_t009_guard_deck"
    private val managerName = "t007_t009_guard_groups"

    /** T-007：定库自证——tool_capabilities 支持矩阵分支回显当前库绝对路径与 cwd。 */
    @Test
    fun toolCapabilities_reportsDatabasePathForDingKu() {
        val result = call("tool_capabilities", "{}")
        assertFalse("tool_capabilities 不应报错：${result.contentJson}", result.isError)

        @Suppress("UNCHECKED_CAST")
        val body = mapper.readValue(result.contentJson, Map::class.java) as Map<String, Any?>

        val dbPath = body["databasePath"] as? String
        assertNotNull("T-007：tool_capabilities 应回显 databasePath（定库凭证）", dbPath)
        assertTrue("T-007：databasePath 应为绝对路径：$dbPath", Paths.get(dbPath!!).isAbsolute)
        assertTrue("T-007：databasePath 应指向 .db 文件：$dbPath", dbPath.endsWith(".db"))
        assertNotNull("T-007：应同时回显 cwd", body["cwd"])
    }

    /** T-009：existingId 引用校验——不存在则报错；传真实 id 则更新行为不变（复用同一 managerId）。 */
    @Test
    fun saveCardGroup_rejectsUnknownExistingId_butAcceptsRealOne() {
        // 0) 造卡池（sourceFile 必须存在，否则先被 sourceFile 校验拦下）
        call(
            "parse_hearthstone_deck_code",
            """{"deckCode":"$deckCode","groupName":"$groupName","enabled":true}"""
        )
        savedFile = Path.of(
            System.getProperty("cardgroup.dir.path", "../data/cardgroup"),
            "$groupName.cardgroup"
        )

        // 1) 先建一个方案，拿到真实 managerId
        val created = call(
            "save_card_group",
            """{"sourceFile":"$groupName","managerName":"$managerName","bindings":[{"name":"守卫组","cardIds":["ICC_820"]}]}"""
        )
        assertFalse("建方案应成功：${created.contentJson}", created.isError)
        @Suppress("UNCHECKED_CAST")
        val createdBody = mapper.readValue(created.contentJson, Map::class.java) as Map<String, Any?>
        val realId = createdBody["managerId"] as? String
        assertNotNull("应返回 managerId", realId)
        managerId = realId  // 交给 McpTestEnv 的 @After cleanup 级联删除

        // 2) 传不存在的 existingId → 必须报错，且信息里带上可用方案
        val bad = call(
            "save_card_group",
            """{"sourceFile":"$groupName","managerName":"$managerName","existingId":"zzzznotexist","bindings":[{"name":"守卫组","cardIds":["ICC_820"]}]}"""
        )
        assertTrue("T-009：不存在的 existingId 应报错", bad.isError)
        assertTrue(
            "T-009：错误信息应说明方案不存在，实际=${bad.contentJson}",
            bad.contentJson.contains("不存在")
        )
        assertTrue(
            "T-009：错误信息应列出可用方案供纠正，实际=${bad.contentJson}",
            bad.contentJson.contains(realId!!)
        )

        // 3) 传真实 existingId → 行为不变：更新成功且复用同一 managerId
        val ok = call(
            "save_card_group",
            """{"sourceFile":"$groupName","managerName":"$managerName","existingId":"$realId","bindings":[{"name":"守卫组","cardIds":["ICC_820","GDB_138"]}]}"""
        )
        assertFalse("T-009：真实 existingId 应更新成功：${ok.contentJson}", ok.isError)
        @Suppress("UNCHECKED_CAST")
        val okBody = mapper.readValue(ok.contentJson, Map::class.java) as Map<String, Any?>
        assertEquals("T-009：更新应复用同一 managerId", realId, okBody["managerId"])
    }

    /**
     * Q-TG-004（形态 D 配套写侧校验）：`managerId` 是树的**归属卡组**，必须指向真实卡组 ——
     * 悬空归属的树在引擎侧"对任何卡组都不生效"，只在日志 warn ⇒ 在写入边界直接拒绝。
     */
    @Test
    fun createDraftTree_rejectsUnknownManagerId() {
        val rootJson = """{"Leaf":{"payload":{"Rule":{"nodeId":"r1"}}}}"""

        // 归属卡组不存在 ⇒ 拒绝
        val bad = call(
            "create_draft_tree",
            """{"name":"q004_悬空归属","bindingType":"PURPOSE_TAG","bindingIds":["CLEAN"],"root":$rootJson,"managerId":"zzz_no_such_deck"}"""
        )
        assertTrue("Q-TG-004：不存在的归属卡组应被拒绝：${bad.contentJson}", bad.isError)
        assertTrue(
            "错误信息应说明卡组不存在：${bad.contentJson}",
            bad.contentJson.contains("不存在")
        )

        // 不填 managerId ⇒ 全局共享树，应通过（回归：新校验不得误伤非 GROUP 绑定）
        val ok = call(
            "create_draft_tree",
            """{"name":"q004_全局树","bindingType":"PURPOSE_TAG","bindingIds":["CLEAN"],"root":$rootJson}"""
        )
        assertFalse("不填 managerId（全局共享树）应通过：${ok.contentJson}", ok.isError)
    }
}
