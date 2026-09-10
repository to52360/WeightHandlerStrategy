package lin.mcp

import com.fasterxml.jackson.databind.JsonNode
import lin.repository.card_purpose.CardPurposeRepository
import lin.repository.card_purpose.PurposeTagDefRepository
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.koin.core.context.GlobalContext

/**
 * T-TG-001：标记定义登记（`save_purpose_tag_def`）+ 用途绑定在打标时展开（实现形态 A：展开式）。
 *
 * 验证：
 * ① 自定义标记登记后即可打标（白名单随定义落库而**可增长**，防幻觉门禁保留）；
 * ② 绑定战略用途后，打标自动把绑定目标拍平进 `purpose_tags`（引擎侧零改动）；
 * ③ 绑定目标只能是战略用途，禁止绑到自定义标记（D-TG-002：只允许一层，禁链式）；
 * ④ 内置战略用途的 `boundPurpose` 恒为 null（它们自己就是战略用途）。
 */
class PurposeTagBindingTest : McpTestEnv() {

    // 必须是 hs.cards 真实存在的卡（findByCardId 主表是 hs.cards）
    private val cardId = "CORE_EX1_606"
    private val plainTag = "TG_TEST_PLAIN"
    private val boundTag = "TG_TEST_BOUND"

    private var originalTags: List<String> = emptyList()

    @Before
    fun snapshot() {
        val entity = GlobalContext.get().get<CardPurposeRepository>().findByCardId(cardId)
        originalTags = entity?.purposeTags?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
    }

    @After
    fun cleanTagDefs() {
        val defs = GlobalContext.get().get<PurposeTagDefRepository>()
        defs.deleteByTagId(plainTag)
        defs.deleteByTagId(boundTag)
        // 本测试连的是项目根源开发库，勿留脏数据：还原该卡原有打标
        call(
            "save_card_purpose",
            mapper.writeValueAsString(mapOf("cardIds" to listOf(cardId), "purposeTags" to originalTags))
        )
    }

    private fun savedTags(): Set<String> =
        GlobalContext.get().get<CardPurposeRepository>()
            .findByCardId(cardId)!!.purposeTags
            .split(",").filter { it.isNotBlank() }.toSet()

    @Test
    fun `登记纯标记后即可打标`() {
        val def = call(
            "save_purpose_tag_def",
            """{"tagId":"$plainTag","displayName":"测试纯标记"}"""
        )
        assertEquals(false, def.isError)

        val save = call("save_card_purpose", """{"cardIds":["$cardId"],"purposeTags":["$plainTag"]}""")
        assertEquals(false, save.isError)
        assertEquals(setOf(plainTag), savedTags())
    }

    @Test
    fun `绑定战略用途后打标自动展开`() {
        call(
            "save_purpose_tag_def",
            """{"tagId":"$boundTag","displayName":"测试绑定","boundPurpose":"CLEAN"}"""
        )
        val save = call("save_card_purpose", """{"cardIds":["$cardId"],"purposeTags":["$boundTag"]}""")
        assertEquals(false, save.isError)

        // 展开式：绑定目标被拍平进 purpose_tags，故引擎侧看到的就是两个普通标签
        assertEquals(setOf(boundTag, "CLEAN"), savedTags())
    }

    @Test
    fun `绑定目标只能是战略用途`() {
        // 先登记一个自定义标记（非内置），再试图绑到它 → 应被拒
        call("save_purpose_tag_def", """{"tagId":"$plainTag","displayName":"测试纯标记"}""")
        val illegal = call(
            "save_purpose_tag_def",
            """{"tagId":"TG_TEST_X","displayName":"非法","boundPurpose":"$plainTag"}"""
        )
        assertTrue("绑到自定义标记应被拒", illegal.isError)
        assertNull(GlobalContext.get().get<PurposeTagDefRepository>().findByTagId("TG_TEST_X"))
    }

    @Test
    fun `内置战略用途的 boundPurpose 恒为 null`() {
        call("save_purpose_tag_def", """{"tagId":"FINISH","displayName":"斩杀","boundPurpose":"CLEAN"}""")
        val json: JsonNode = mapper.readTree(
            call("get", """{"resource":"purpose_tag","id":"FINISH"}""").contentJson
        )
        assertTrue("内置战略用途不应被绑定", json.get("boundPurpose")?.isNull ?: true)
    }

    @Test
    fun `战略用途不可删除`() {
        val r = call("delete", """{"resource":"purpose_tag","id":"CLEAN"}""")
        assertTrue("内置 7 个战略用途应不可删", r.isError)
        assertNotNull(GlobalContext.get().get<PurposeTagDefRepository>().findByTagId("CLEAN"))
    }

    @Test
    fun `被引用的标记不可删除_摘标后可删并能恢复`() {
        val defs = GlobalContext.get().get<PurposeTagDefRepository>()
        call("save_purpose_tag_def", """{"tagId":"$plainTag","displayName":"测试纯标记"}""")
        call("save_card_purpose", """{"cardIds":["$cardId"],"purposeTags":["$plainTag"]}""")

        // 1. 仍被卡牌引用 → 拒绝（防幽灵标记：定义没了但条件树仍能命中该字符串）
        assertTrue("被引用时删除应被拒", call("delete", """{"resource":"purpose_tag","id":"$plainTag"}""").isError)

        // 2. 摘标后可删
        call("save_card_purpose", """{"cardIds":["$cardId"],"purposeTags":[]}""")
        val deleted = call("delete", """{"resource":"purpose_tag","id":"$plainTag"}""")
        assertEquals(false, deleted.isError)
        assertNull("删除后定义应不存在", defs.findByTagId(plainTag))

        // 3. 按原 tagId 一键恢复
        val snapshotId = mapper.readTree(deleted.contentJson).get("snapshotId").asText()
        val restored = call("restore_snapshot", """{"snapshotId":"$snapshotId"}""")
        assertEquals(false, restored.isError)
        assertNotNull("恢复后定义应写回", defs.findByTagId(plainTag))
    }
}
