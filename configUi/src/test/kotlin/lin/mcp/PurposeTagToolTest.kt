package lin.mcp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PurposeTagToolTest : McpTestEnv() {

    @Test
    fun testPurposeTagListAll() {
        val resp = call("purpose_tag", """{"action":"LIST"}""")
        assertFalse("purpose_tag LIST 不应报错", resp.isError)
        val json = resp.contentJson
        assertTrue("应包含 SAVE_LIFE", json.contains("SAVE_LIFE"))
        assertTrue("应包含 CLEAN", json.contains("CLEAN"))
        assertTrue("应包含 FINISH", json.contains("FINISH"))
        assertTrue("应包含 GREED", json.contains("GREED"))
        assertTrue("应包含 VALUE", json.contains("VALUE"))
        assertTrue("应包含 EXTRA_COST", json.contains("EXTRA_COST"))
        assertTrue("应包含默认阶段 DEFEND", json.contains("DEFEND"))
    }

    @Test
    fun testPurposeTagGetSuccess() {
        val resp = call("purpose_tag", """{"action":"GET","tagId":"SAVE_LIFE"}""")
        assertFalse("purpose_tag GET SAVE_LIFE 不应报错", resp.isError)
        val json = resp.contentJson
        assertTrue("应包含 SAVE_LIFE", json.contains("SAVE_LIFE"))
        assertTrue("应包含保命显示名", json.contains("保命"))
        assertTrue("应包含出牌阶段 DEFEND", json.contains("DEFEND"))
        assertTrue("应包含 priority", json.contains("priority"))
    }

    @Test
    fun testPurposeTagGetMissingTagId() {
        val resp = call("purpose_tag", """{"action":"GET"}""")
        assertTrue("未传 tagId 的 GET 应返回 isError=true", resp.isError)
        assertTrue("应提示提供合法的 tagId", resp.contentJson.contains("action=GET 必须提供合法的 tagId"))
    }

    @Test
    fun testPurposeTagInvalidAction() {
        val resp = call("purpose_tag", """{"action":"UNKNOWN"}""")
        assertTrue("未知 action 应返回 error", resp.isError)
        assertTrue("错误提示应说明支持 LIST, GET", resp.contentJson.contains("有效值仅限：LIST, GET"))
    }

    @Test
    fun testCreateDraftTreeWithPurposeTagBinding() {
        val reqJson = """
            {"CreateDraftTree": {
                "name": "保命用途标签测试评估树",
                "bindingType": "PURPOSE_TAG",
                "bindingIds": ["SAVE_LIFE"],
                "root": {"Leaf":{"payload":{"Rule":{"nodeId":"leaf_1"}}}}
            }}
        """.trimIndent()

        val resp = call("create_draft_tree", reqJson)
        assertFalse("PURPOSE_TAG 绑定创建草稿不应被拦截: ${resp.contentJson}", resp.isError)
        assertTrue("响应应包含 draftId", resp.contentJson.contains("draftId"))
    }
}
