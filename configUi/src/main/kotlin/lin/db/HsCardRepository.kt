package lin.db

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

    /**
     * 按 Hearthstone dbfId 批量查询卡牌的 cardId / name / text，返回以 dbfId 为键的 Map。
     * 炉石卡组代码（deck string）中携带的卡牌标识即 dbfId，因此解析卡组代码时用此方法。
     * 仅返回在 [hs.cards] 中存在记录的卡（本地库缺卡时不返回），便于按卡组出现顺序重排。
     */
    fun findCardMapByDbfIds(dbfIds: List<Int>): Map<Int, CardIdNameText> {
        if (dbfIds.isEmpty()) return emptyMap()
        val placeholders = dbfIds.joinToString(",") { "?" }
        val sql = """
            SELECT dbfId, cardId, name, text FROM hs.cards
            WHERE dbfId IN ($placeholders)
        """.trimIndent()
        val rowMapper = RowMapper { rs, _ ->
            val dbfId = rs.getInt("dbfId")
            dbfId to CardIdNameText(
                cardId = rs.getString("cardId"),
                name = rs.getString("name"),
                text = rs.getString("text")
            )
        }
        return jdbcTemplate.query(sql, rowMapper, *dbfIds.toTypedArray()).toMap()
    }
}

data class CardIdNameText(val cardId: String, val name: String, val text: String?)