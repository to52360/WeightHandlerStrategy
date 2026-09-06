package lin.mcp

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Path

/**
 * T-004（sop-rework）：`save_card_group` 分组级 `surplusIdleThreshold` 的 MCP 清除通道。
 *
 * 覆盖：①设组级门槛 → get 回读可见 ②传 0 清除 → get 回读为 null（还原付得起即垫）
 * ③缺省（null）保留原值不误清 ④非法值（>9）报错、不静默写入。
 *
 * 环境（Koin / DB / 工具注册 / 清理）复用 [McpTestEnv]；本类只写断言。
 */
class T004GroupGateClearTest : McpTestEnv() {

    private val deckCode =
        "AAEBAZ8FBPfQArjFBdaABtK5Bg3YxwKd7ALZ/gL9uAPruQPTvQTi0wSZjgb1lQbt3waS4Aac6Aaf6AYAAA=="
    private val groupName = "t004_gate_clear_deck"
    private val managerName = "t004_gate_clear_groups"

    private fun createManager(surplusJson: String): String {
        val resp = call(
            "save_card_group",
            """{"sourceFile":"$groupName","managerName":"$managerName","bindings":[{"name":"守卫组","cardIds":["ICC_820"],"surplusIdleThreshold":$surplusJson}]}"""
        )
        assertFalse("建方案应成功：${resp.contentJson}", resp.isError)
        @Suppress("UNCHECKED_CAST")
        val body = mapper.readValue(resp.contentJson, Map::class.java) as Map<String, Any?>
        val id = body["managerId"] as? String
        assertNotNull("应返回 managerId", id)
        return id!!
    }

    private fun updateManager(id: String, surplusJson: String?): McpToolResult {
        val bindingsJson = if (surplusJson == null) {
            """{"name":"守卫组","cardIds":["ICC_820"]}"""
        } else {
            """{"name":"守卫组","cardIds":["ICC_820"],"surplusIdleThreshold":$surplusJson}"""
        }
        return call(
            "save_card_group",
            """{"sourceFile":"$groupName","managerName":"$managerName","existingId":"$id","bindings":[$bindingsJson]}"""
        )
    }

    private fun gateOfManager(id: String): Int? {
        val getResp = call("get", """{"resource":"card_group","id":"$id"}""")
        assertFalse("get(card_group) 应成功：${getResp.contentJson}", getResp.isError)
        @Suppress("UNCHECKED_CAST")
        val body = mapper.readValue(getResp.contentJson, Map::class.java) as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val bindings = body["bindings"] as List<Map<String, Any?>>
        return bindings[0]["surplusIdleThreshold"] as? Int
    }

    /** 造卡池 + 建带门槛方案，返回 managerId（已登记 @After cleanup）。 */
    private fun setupManagerWithGate(): String {
        call(
            "parse_hearthstone_deck_code",
            """{"deckCode":"$deckCode","groupName":"$groupName","enabled":true}"""
        )
        savedFile = Path.of(
            System.getProperty("cardgroup.dir.path", "../data/cardgroup"),
            "$groupName.cardgroup"
        )
        val id = createManager("2")
        managerId = id
        return id
    }

    /** T-004a：设组级门槛回读可见；传 0 清除后回读为 null（还原付得起即垫）。 */
    @Test
    fun groupGate_setThenClearByZero() {
        val id = setupManagerWithGate()
        assertEquals("组级门槛应设为 2", 2, gateOfManager(id))

        val clear = updateManager(id, "0")
        assertFalse("传 0 清除应成功：${clear.contentJson}", clear.isError)
        assertNull("门槛应被清除（null = 付得起即垫）", gateOfManager(id))
    }

    /** T-004b：缺省（不传 surplusIdleThreshold）保留原值，不误清。 */
    @Test
    fun groupGate_absentKeepsExisting() {
        val id = setupManagerWithGate()

        val keep = updateManager(id, null)
        assertFalse("缺省保留应成功：${keep.contentJson}", keep.isError)
        assertEquals("缺省（null）应保留原门槛 2", 2, gateOfManager(id))
    }

    /** T-004c：非法值（>9）报错、不静默写入，原门槛不受破坏。 */
    @Test
    fun groupGate_invalidValueRejected() {
        val id = setupManagerWithGate()

        val bad = updateManager(id, "15")
        assertTrue("非法值 15 应报错：${bad.contentJson}", bad.isError)
        assertTrue("错误信息应提示取值范围：${bad.contentJson}", bad.contentJson.contains("1~9"))
        assertEquals("报错后原门槛应保持 2", 2, gateOfManager(id))
    }
}
