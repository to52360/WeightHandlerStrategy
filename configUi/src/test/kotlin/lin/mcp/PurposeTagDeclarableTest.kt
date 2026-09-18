package lin.mcp

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D-DP-004（Q-DP-001 晋级制）最小验证集：可声明作用候选 = 内置能力常量 ∪ 库中晋级行。
 *
 * 覆盖：内置边界（5 个可声明 / FINISH·EXTRA_COST 恒不可）、自定义标记未晋级被拒 → 晋级后可声明、
 * 晋级 ↔ 绑定互斥、降级守卫（仍被声明引用时拒）、改名保存不丢 declarable。
 */
class PurposeTagDeclarableTest : McpTestEnv() {

    private val tag = "DP_TEST_BURST"
    private val presetIds = mutableListOf<String>()

    @After
    fun cleanupDeclarableTest() {
        // 先删预设（解除声明引用）再删自定义标记 —— 顺序反了会被守卫拒绝
        presetIds.forEach { id ->
            runCatching { call("delete", """{"resource":"strategy_preset","id":"$id"}""") }
        }
        runCatching { call("delete", """{"resource":"purpose_tag","id":"$tag"}""") }
    }

    private fun savePreset(json: String): String {
        val r = call("save_strategy_preset", json)
        assertFalse("保存预设应成功: ${r.contentJson}", r.isError)
        val id = mapper.readTree(r.contentJson).get("presetId").asText()
        presetIds += id
        return id
    }

    private fun declare(json: String) = call("save_strategy_preset", json)

    /** 内置 5 个可声明；FINISH / EXTRA_COST 恒不可声明（Q-033 / T-TG-031）。 */
    @Test
    fun `内置可声明边界`() {
        savePreset("""{"name":"DP_TEST_P_CLEAN","timings":[{"tagId":"CLEAN","defaultStage":"LATE"}]}""")
        listOf("FINISH", "EXTRA_COST").forEach { t ->
            val r = declare("""{"name":"DP_TEST_P_$t","timings":[{"tagId":"$t","defaultStage":"LATE"}]}""")
            assertTrue("$t 不应可声明: ${r.contentJson}", r.isError)
            assertTrue("应提示不是可声明的作用", r.contentJson.contains("不是可声明的作用"))
        }
    }

    /** 自定义标记：默认不可声明 → 晋级后可声明；list 回显 declarable。 */
    @Test
    fun `未晋级被拒且晋级后可声明`() {
        val reg = call("save_purpose_tag_def", """{"tagId":"$tag","displayName":"测试爆发"}""")
        assertFalse("登记标记应成功: ${reg.contentJson}", reg.isError)
        assertFalse("默认 declarable 应为 false", mapper.readTree(reg.contentJson).get("declarable").asBoolean())

        val rejected = declare("""{"name":"DP_TEST_P_BEFORE","timings":[{"tagId":"$tag","defaultStage":"LATE"}]}""")
        assertTrue("未晋级标记不应可声明: ${rejected.contentJson}", rejected.isError)

        val promoted = call("save_purpose_tag_def", """{"tagId":"$tag","displayName":"测试爆发","declarable":true}""")
        assertFalse("晋级应成功: ${promoted.contentJson}", promoted.isError)
        assertTrue("晋级后 declarable 应为 true", mapper.readTree(promoted.contentJson).get("declarable").asBoolean())

        val summary = mapper.readTree(call("list", """{"resource":"purpose_tag"}""").contentJson)
            .first { it.get("tagId").asText() == tag }
        assertTrue("list 应回显 declarable=true", summary.get("declarable").asBoolean())

        savePreset("""{"name":"DP_TEST_P_AFTER","timings":[{"tagId":"$tag","defaultStage":"LATE"}]}""")
    }

    /** 晋级 ↔ 绑定互斥（继承别人的行为 vs 自己就是作用）。 */
    @Test
    fun `晋级与绑定互斥`() {
        val r = call(
            "save_purpose_tag_def",
            """{"tagId":"$tag","displayName":"测试绑定","boundPurpose":"CLEAN","declarable":true}"""
        )
        assertTrue("互斥应报错: ${r.contentJson}", r.isError)
        assertTrue("错误应提示互斥", r.contentJson.contains("互斥"))
    }

    /** 降级守卫：仍被声明引用 ⇒ 拒；移除声明后可降级。 */
    @Test
    fun `降级守卫`() {
        call("save_purpose_tag_def", """{"tagId":"$tag","displayName":"测试降级","declarable":true}""")
        val pid = savePreset("""{"name":"DP_TEST_P_DEMOTE","timings":[{"tagId":"$tag","defaultStage":"LATE"}]}""")

        val denied = call("save_purpose_tag_def", """{"tagId":"$tag","displayName":"测试降级","declarable":false}""")
        assertTrue("仍被声明引用时不可降级: ${denied.contentJson}", denied.isError)
        assertTrue("应提示不可降级", denied.contentJson.contains("不可降级"))

        val del = call("delete", """{"resource":"strategy_preset","id":"$pid"}""")
        assertFalse("删除预设应成功: ${del.contentJson}", del.isError)
        presetIds.remove(pid)

        val ok = call("save_purpose_tag_def", """{"tagId":"$tag","displayName":"测试降级","declarable":false}""")
        assertFalse("移除声明后应可降级: ${ok.contentJson}", ok.isError)
    }

    /** `declarable = null`（不修改）时改名保存不能把已晋级状态重置（save 的 INSERT 列 + DO UPDATE SET 都要带该列）。 */
    @Test
    fun `改名保存不丢 declarable`() {
        call("save_purpose_tag_def", """{"tagId":"$tag","displayName":"测试改名","declarable":true}""")
        val rename = call("save_purpose_tag_def", """{"tagId":"$tag","displayName":"测试改名2"}""")
        assertFalse("改名应成功: ${rename.contentJson}", rename.isError)
        assertTrue("改名后 declarable 应保留", mapper.readTree(rename.contentJson).get("declarable").asBoolean())

        val summary = mapper.readTree(call("list", """{"resource":"purpose_tag"}""").contentJson)
            .first { it.get("tagId").asText() == tag }
        assertTrue("list 中 declarable 应保留", summary.get("declarable").asBoolean())
    }
}
