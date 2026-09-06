package lin.repository.card_purpose

import lin.bean.usePlan.PurposeTagId
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import java.nio.file.Files

/**
 * Q-015 回归：`findByCardId` 从 `hs.cards LEFT JOIN card_purpose` 起查，首次打标（card_purpose 无行）
 * 时 p 侧全 NULL，而实体 purposeTags 声明非空 String → rowMapper NPE。修复为 COALESCE 空串兜底。
 * 覆盖：首打标往返、重存幂等、hs 库缺行卡兜底。
 */
class CardPurposeFirstTagRoundTripTest {

    private lateinit var dataSource: SingleConnectionDataSource
    private lateinit var repository: CardPurposeRepository
    private val dbFile = Files.createTempFile("purpose_first_tag_test", ".db")
    private val hsFile = Files.createTempFile("purpose_first_tag_hs", ".db")

    @Before
    fun setup() {
        dataSource = SingleConnectionDataSource("jdbc:sqlite:$dbFile", true)
        val jdbcTemplate = JdbcTemplate(dataSource)
        // 生产经 connectionInitSql 外接 hs_cards.db；测试对等：临时文件 ATTACH 成 hs
        jdbcTemplate.execute("ATTACH DATABASE '$hsFile' AS hs")
        jdbcTemplate.execute("CREATE TABLE hs.cards (cardId TEXT PRIMARY KEY, name TEXT)")
        jdbcTemplate.execute("INSERT INTO hs.cards (cardId, name) VALUES ('TID_098', '纳迦侍从')")
        repository = CardPurposeRepository(jdbcTemplate)
    }

    @After
    fun teardown() {
        dataSource.close()
        Files.deleteIfExists(dbFile)
        Files.deleteIfExists(hsFile)
    }

    @Test
    fun `首次打标不NPE且回显卡名`() {
        // 未打标卡：实体应回显 hs 卡名 + 空标签（Q-015 修复点）
        val existing = repository.findByCardId("TID_098")
        assertNotNull(existing)
        assertEquals("纳迦侍从", existing?.name)
        assertEquals("", existing?.purposeTags)
        assertTrue(existing?.toDomain()?.purposeTags?.isEmpty() == true)

        // 首打标走 saveAll（与 MCP handleSave 同路径）→ 复查
        repository.saveAll(
            listOf(
                CardPurposeEntity(
                    cardId = "TID_098", name = existing?.name,
                    purposeTags = "DRAW_CARD", replanAfterUse = false, createdDate = "2026-09-06"
                )
            )
        )
        val tagged = repository.findByCardId("TID_098")
        assertEquals(setOf(PurposeTagId("DRAW_CARD")), tagged?.toDomain()?.purposeTags)
    }

    @Test
    fun `配置模式分页未打标卡空串兜底`() {
        // Q-015 同类修复点：findConfigPage 与 findByCardId 同为 hs LEFT JOIN 方向且共用 rowMapper，
        // 未打标卡无 COALESCE 则分页即 NPE；「未配置用途」筛选项更是专门命中 NULL 行
        assertEquals(1, repository.countConfig(setOf("TID_098"), "", "未配置用途", null))

        val page = repository.findConfigPage(setOf("TID_098"), "", null, null, limit = 10, offset = 0)
        assertEquals(1, page.size)
        assertEquals("纳迦侍从", page[0].name)
        assertEquals("", page[0].purposeTags)

        val untagged = repository.findConfigPage(setOf("TID_098"), "", "未配置用途", null, limit = 10, offset = 0)
        assertEquals(1, untagged.size)
        assertEquals("纳迦侍从", untagged[0].name)
    }

    @Test
    fun `重存幂等不产生重复行`() {
        val entity = CardPurposeEntity(
            cardId = "TID_098", name = "纳迦侍从",
            purposeTags = "DRAW_CARD", replanAfterUse = false, createdDate = "2026-09-06"
        )
        repository.saveAll(listOf(entity))
        repository.saveAll(listOf(entity))

        val rows = JdbcTemplate(dataSource)
            .queryForList("SELECT purpose_tags FROM card_purpose WHERE card_id = 'TID_098'")
        assertEquals(1, rows.size)
        assertEquals("DRAW_CARD", rows[0]["purpose_tags"])
    }

    @Test
    fun `hs库缺行卡打标兜底`() {
        // hs.cards 无此卡：findByCardId 返回 null，save 仍可落行（INSERT 不依赖 hs）
        assertNull(repository.findByCardId("XXXX_999"))
        repository.save(
            CardPurposeEntity(
                cardId = "XXXX_999", name = null,
                purposeTags = "VALUE", replanAfterUse = false, createdDate = "2026-09-06"
            )
        )
        // card_purpose 有行而 hs 无行：findAll 路径 name 为 null 不炸
        val all = repository.findAll()
        val orphan = all.first { it.cardId == "XXXX_999" }
        assertEquals("VALUE", orphan.purposeTags)
        assertNull(orphan.name)
    }
}
