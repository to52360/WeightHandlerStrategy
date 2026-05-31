package lin.card_purpose.db

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper

class CardPurposeRepository(private val jdbcTemplate: JdbcTemplate) {

    init {
        initSchema()
    }

    private fun initSchema() {
        val sql = """
            CREATE TABLE IF NOT EXISTS card_purpose (
                card_id TEXT PRIMARY KEY,
                purpose_tags TEXT NOT NULL,
                replan_after_use INTEGER NOT NULL DEFAULT 0
            );
        """.trimIndent()
        jdbcTemplate.execute(sql)
    }

    private val rowMapper = RowMapper { rs, _ ->
        CardPurposeEntity(
            cardId = rs.getString("card_id"),
            purposeTags = rs.getString("purpose_tags"),
            replanAfterUse = rs.getInt("replan_after_use") != 0
        )
    }

    fun save(entity: CardPurposeEntity) {
        val sql = """
            INSERT INTO card_purpose (card_id, purpose_tags, replan_after_use)
            VALUES (?, ?, ?)
            ON CONFLICT(card_id) DO UPDATE SET
                purpose_tags   = excluded.purpose_tags,
                replan_after_use = excluded.replan_after_use
        """.trimIndent()
        jdbcTemplate.update(
            sql,
            entity.cardId,
            entity.purposeTags,
            if (entity.replanAfterUse) 1 else 0
        )
    }

    fun findAll(): List<CardPurposeEntity> {
        val sql = "SELECT * FROM card_purpose"
        return jdbcTemplate.query(sql, rowMapper)
    }

    fun findByCardId(cardId: String): CardPurposeEntity? {
        val sql = "SELECT * FROM card_purpose WHERE card_id = ?"
        return jdbcTemplate.query(sql, rowMapper, cardId).firstOrNull()
    }

    fun findByCardIds(cardIds: Set<String>): List<CardPurposeEntity> {
        if (cardIds.isEmpty()) return emptyList()
        val placeholders = cardIds.joinToString(",") { "?" }
        val sql = "SELECT * FROM card_purpose WHERE card_id IN ($placeholders)"
        return jdbcTemplate.query(sql, rowMapper, *cardIds.toTypedArray())
    }

    fun deleteByCardId(cardId: String) {
        val sql = "DELETE FROM card_purpose WHERE card_id = ?"
        jdbcTemplate.update(sql, cardId)
    }
}
