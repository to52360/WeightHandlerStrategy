package lin.utils

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import lin.config.AppConfig
import lin.dao.CardGroupJsonParser
import lin.ui.db.HsCardRepository
import org.junit.Assert.assertThrows
import org.junit.Test
import org.springframework.jdbc.core.JdbcTemplate
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * HearthstoneDeckCodeParser 测试（JUnit 4）。
 *
 * - [decode] 为纯逻辑，不依赖数据库。
 * - 需要查库的用例（[findCardMapByDbfIds] / [parseToCards] / 文件生成）会构造一个
 *   与生产一致的 JdbcTemplate：连内存库并 ATTACH 真实的 hs_cards.db AS hs，
 *   从而能通过 SQL 中的 `hs.cards` 访问卡牌元数据。
 */
class HearthstoneDeckCodeParserTest {

    /** 用户提供的标准炉石卡组代码（战士卡组） */
    private val sampleDeckCode =
        "AAEBAZfDAwaopAOX7wS2xAXHhwfUlweEnQcRpKQDiKAEjtQEo6UF3o0GuZEG5ZUGkJcGnJ4Gn54GyqYGkagGjr8GrsAGweEG7eoGh5wHAAA"

    /** 定位 hs_cards.db：依次尝试相对路径与 AppConfig，回退到首个存在的候选 */
    private fun locateHsCardsDb(): Path {
        val candidates = listOf(
            Path.of("..", "hs_cards.db"),
            Path.of("hs_cards.db"),
            AppConfig.hsCardsDbPath
        )
        return candidates.firstOrNull { Files.exists(it) }
            ?: error("找不到 hs_cards.db，已尝试候选路径: $candidates")
    }

    private fun createRepository(): HsCardRepository {
        val dbPath = locateHsCardsDb().toAbsolutePath()
        val config = HikariConfig().apply {
            driverClassName = "org.sqlite.JDBC"
            jdbcUrl = "jdbc:sqlite::memory:"
            maximumPoolSize = 1
            connectionInitSql = "ATTACH DATABASE '$dbPath' AS hs"
            poolName = "TestHsCardsPool"
        }
        return HsCardRepository(JdbcTemplate(HikariDataSource(config)))
    }

    // ---------- 纯解码（不依赖数据库） ----------

    @Test
    fun `decode 正确解析卡组代码结构`() {
        val deck = HearthstoneDeckCodeParser.decode(sampleDeckCode)

        assertEquals(1, deck.format, "format 应为 1")
        assertEquals(listOf(57751), deck.heroes, "英雄应为 57751（腐化督军加尔鲁什）")
        assertEquals(23, deck.cards.size, "共 23 张卡（去重前含数量分组）")

        assertEquals(HearthstoneDeckCodeParser.DeckCodeCard(53800, 1), deck.cards.first())
        assertTrue(deck.cards.any { it.dbfId == 53796 && it.count == 2 }, "53796 应为 2 张")
    }

    @Test
    fun `decode 对非法代码抛出异常`() {
        assertThrows(IllegalArgumentException::class.java) {
            HearthstoneDeckCodeParser.decode("这不是合法的卡组代码")
        }
    }

    // ---------- 依赖数据库 ----------

    @Test
    fun `findCardMapByDbfIds 仅返回库中存在的卡`() {
        val repo = createRepository()
        // 53800 / 53796 在库；115655 为本地缺卡
        val map = repo.findCardMapByDbfIds(listOf(53800, 53796, 115655))

        assertEquals(2, map.size, "本地库缺 115655，应只返回 2 张")
        assertEquals("ULD_258", map[53800]?.cardId)
        assertEquals("ULD_256", map[53796]?.cardId)
        assertFalse(map.containsKey(115655))
    }

    @Test
    fun `parseToCards 去重并返回 CardIdNameText 列表`() {
        val repo = createRepository()
        val cards = HearthstoneDeckCodeParser.parseToCards(sampleDeckCode, repo)

        // 23 个 distinct dbfId 中 19 个在库
        assertEquals(19, cards.size, "应返回 19 张（缺卡不出现）")
        assertEquals("ULD_258", cards.first().cardId, "按卡组顺序，首张应为 53800=ULD_258")
        assertTrue(cards.all { it.cardId.isNotBlank() && it.name.isNotBlank() }, "cardId/name 均非空")
        assertEquals(cards.distinctBy { it.cardId }.size, cards.size, "结果已按 dbfId 去重")
    }

    @Test
    fun `saveCardGroup 写出可回读的 cardgroup 文件`() {
        val repo = createRepository()
        val cards = HearthstoneDeckCodeParser.parseToCards(sampleDeckCode, repo)

        val tmpDir = Files.createTempDirectory("cardgroup_test")
        try {
            val file = CardGroupJsonParser.saveCardGroup(cards, "test_group", enabled = true, dirPath = tmpDir)
            assertTrue(Files.exists(file), "文件应已生成")
            assertEquals("test_group.cardgroup", file.fileName.toString())

            val loaded = CardGroupJsonParser.loadByFileName("test_group", dirPath = tmpDir)
            assertTrue(loaded != null, "文件应可回读")
            assertEquals(true, loaded!!.enabled)
            assertEquals(19, loaded.cards.size, "回读卡数应一致")
            assertEquals("ULD_258", loaded.cards.first().cardId)
        } finally {
            Files.list(tmpDir).use { it.forEach { p -> Files.deleteIfExists(p) } }
            Files.deleteIfExists(tmpDir)
        }
    }

    @Test
    fun `parseDeckCodeToCardGroupFile 端到端生成文件并清理`() {
        val repo = createRepository()
        val fileName = "test_end2end"
        val file = HearthstoneDeckCodeParser.parseDeckCodeToCardGroupFile(
            sampleDeckCode, repo, fileName, enabled = true
        )
        try {
            assertTrue(Files.exists(file), "端到端文件应已生成于默认目录")
            val loaded = CardGroupJsonParser.loadByFileName(fileName)
            assertTrue(loaded != null, "默认目录文件应可回读")
            assertEquals(19, loaded!!.cards.size)
        } finally {
            // 清理默认目录（cardgroup.dir.path）下生成的测试文件，避免污染
            Files.deleteIfExists(file)
        }
    }
}
