package lin.card_use.db

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper

class CardUseConfigRepository(private val jdbcTemplate: JdbcTemplate) {

    init {
        initSchema()
    }

    private fun initSchema() {
        val sql = """
            CREATE TABLE IF NOT EXISTS card_use_config (
                card_group_id TEXT PRIMARY KEY,
                purpose_tags TEXT NOT NULL,
                tags TEXT NOT NULL,
                stage_override TEXT
            );
        """.trimIndent()
        jdbcTemplate.execute(sql)
    }

    private val rowMapper = RowMapper { rs, _ ->
        CardUseConfigEntity(
            cardGroupId = rs.getString("card_group_id"),
            purposeTags = rs.getString("purpose_tags"),
            tags = rs.getString("tags"),
            stageOverride = rs.getString("stage_override")
        )
    }

    fun save(entity: CardUseConfigEntity) {
        val sql = """
            INSERT INTO card_use_config (card_group_id, purpose_tags, tags, stage_override)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(card_group_id) DO UPDATE SET
                purpose_tags   = excluded.purpose_tags,
                tags           = excluded.tags,
                stage_override = excluded.stage_override
        """.trimIndent()
        jdbcTemplate.update(
            sql,
            entity.cardGroupId,
            entity.purposeTags,
            entity.tags,
            entity.stageOverride
        )
    }

    fun findAll(): List<CardUseConfigEntity> {
        val sql = "SELECT * FROM card_use_config"
        return jdbcTemplate.query(sql, rowMapper)
    }

    fun findByCardGroupId(cardGroupId: String): CardUseConfigEntity? {
        val sql = "SELECT * FROM card_use_config WHERE card_group_id = ?"
        return jdbcTemplate.query(sql, rowMapper, cardGroupId).firstOrNull()
    }

    fun deleteByCardGroupId(cardGroupId: String) {
        val sql = "DELETE FROM card_use_config WHERE card_group_id = ?"
        jdbcTemplate.update(sql, cardGroupId)
    }
}
