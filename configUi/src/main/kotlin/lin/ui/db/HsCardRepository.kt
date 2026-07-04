package lin.ui.db

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper

/**
 * hs.cards 卡牌元数据只读查询，与 card_purpose 业务无关。
 */
class HsCardRepository(private val jdbcTemplate: JdbcTemplate) {

    fun findName(cardId: String): String? {
        val sql = "SELECT name FROM hs.cards WHERE cardId = ?"
        return try {
            jdbcTemplate.queryForObject(sql, String::class.java, cardId)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 按 cardId 或 name 模糊搜索，返回 (cardId, name) 列表。
     */
    fun search(query: String, limit: Int = 30): List<Pair<String, String>> {
        if (query.isBlank()) return emptyList()
        val pattern = "%$query%"
        val sql = """
            SELECT cardId, name FROM hs.cards
            WHERE cardId LIKE ? OR name LIKE ?
            LIMIT ?
        """.trimIndent()
        return jdbcTemplate.query(sql, { rs, _ ->
            rs.getString("cardId") to rs.getString("name")
        }, pattern, pattern, limit)
    }

    /**
     * 批量查询卡牌的 cardId / name / text。
     * text 为卡牌效果描述，可能为 null（hs.cards 中无对应记录时该卡返回 null）。
     */
    fun findCardByIds(cardIds: List<String>): List<CardIdNameText> {
        if (cardIds.isEmpty()) return emptyList()
        val placeholders = cardIds.joinToString(",") { "?" }
        val sql = """
            SELECT cardId, name, text FROM hs.cards
            WHERE cardId IN ($placeholders)
        """.trimIndent()
        val rowMapper = RowMapper { rs, _ ->
            CardIdNameText(
                cardId = rs.getString("cardId"),
                name = rs.getString("name"),
                text = rs.getString("text")
            )
        }
        return jdbcTemplate.query(sql, rowMapper, *cardIds.toTypedArray())
    }
}

data class CardIdNameText(val cardId: String, val name: String, val text: String?)