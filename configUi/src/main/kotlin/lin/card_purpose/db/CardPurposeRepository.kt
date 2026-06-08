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
                name TEXT,
                purpose_tags TEXT NOT NULL,
                replan_after_use INTEGER NOT NULL DEFAULT 0
            );
        """.trimIndent()
        jdbcTemplate.execute(sql)

        // 尝试动态添加 name 字段以兼容已存在的数据库
        try {
            jdbcTemplate.execute("ALTER TABLE card_purpose ADD COLUMN name TEXT;")
        } catch (e: Exception) {
            // 若字段已存在则会报错，直接忽略即可
        }
    }

    private val rowMapper = RowMapper { rs, _ ->
        CardPurposeEntity(
            cardId = rs.getString("card_id"),
            name = rs.getString("name"),
            purposeTags = rs.getString("purpose_tags"),
            replanAfterUse = rs.getInt("replan_after_use") != 0
        )
    }

    fun save(entity: CardPurposeEntity) {
        val sql = """
            INSERT INTO card_purpose (card_id, name, purpose_tags, replan_after_use)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(card_id) DO UPDATE SET
                name             = excluded.name,
                purpose_tags     = excluded.purpose_tags,
                replan_after_use = excluded.replan_after_use
        """.trimIndent()
        jdbcTemplate.update(
            sql,
            entity.cardId,
            entity.name,
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

    fun syncCards(cards: List<CardPurposeEntity>) {
        if (cards.isEmpty()) return
        val sql = """
            INSERT INTO card_purpose (card_id, name, purpose_tags, replan_after_use)
            VALUES (?, ?, '', 0)
            ON CONFLICT(card_id) DO UPDATE SET
                name = excluded.name
        """.trimIndent()

        jdbcTemplate.execute("BEGIN TRANSACTION;")
        try {
            jdbcTemplate.batchUpdate(
                sql,
                object : org.springframework.jdbc.core.BatchPreparedStatementSetter {
                    override fun setValues(ps: java.sql.PreparedStatement, i: Int) {
                        val card = cards[i]
                        ps.setString(1, card.cardId)
                        ps.setString(2, card.name ?: "")
                    }

                    override fun getBatchSize() = cards.size
                }
            )
            jdbcTemplate.execute("COMMIT;")
        } catch (e: Exception) {
            jdbcTemplate.execute("ROLLBACK;")
            throw e
        }
    }

    fun saveAll(entities: List<CardPurposeEntity>) {
        if (entities.isEmpty()) return
        val sql = """
            INSERT INTO card_purpose (card_id, name, purpose_tags, replan_after_use)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(card_id) DO UPDATE SET
                name             = excluded.name,
                purpose_tags     = excluded.purpose_tags,
                replan_after_use = excluded.replan_after_use
        """.trimIndent()

        jdbcTemplate.execute("BEGIN TRANSACTION;")
        try {
            jdbcTemplate.batchUpdate(
                sql,
                object : org.springframework.jdbc.core.BatchPreparedStatementSetter {
                    override fun setValues(ps: java.sql.PreparedStatement, i: Int) {
                        val entity = entities[i]
                        ps.setString(1, entity.cardId)
                        ps.setString(2, entity.name)
                        ps.setString(3, entity.purposeTags)
                        ps.setInt(4, if (entity.replanAfterUse) 1 else 0)
                    }

                    override fun getBatchSize() = entities.size
                }
            )
            jdbcTemplate.execute("COMMIT;")
        } catch (e: Exception) {
            jdbcTemplate.execute("ROLLBACK;")
            throw e
        }
    }

    fun count(cardIds: Set<String>?, searchText: String, tagFilter: String?): Int {
        val conditions = mutableListOf<String>()
        val params = mutableListOf<Any>()

        if (cardIds != null) {
            if (cardIds.isEmpty()) return 0
            val placeholders = cardIds.joinToString(",") { "?" }
            conditions.add("card_id IN ($placeholders)")
            params.addAll(cardIds)
        }

        if (searchText.isNotBlank()) {
            conditions.add("(card_id LIKE ? OR name LIKE ?)")
            params.add("%$searchText%")
            params.add("%$searchText%")
        }

        if (tagFilter != null) {
            conditions.add("purpose_tags LIKE ?")
            params.add("%$tagFilter%")
        }

        val whereClause = if (conditions.isNotEmpty()) "WHERE ${conditions.joinToString(" AND ")}" else ""
        val sql = "SELECT COUNT(*) FROM card_purpose $whereClause"
        return jdbcTemplate.queryForObject(sql, Int::class.java, *params.toTypedArray()) ?: 0
    }

    fun findPaginated(
        cardIds: Set<String>?,
        searchText: String,
        tagFilter: String?,
        limit: Int,
        offset: Int
    ): List<CardPurposeEntity> {
        val conditions = mutableListOf<String>()
        val params = mutableListOf<Any>()

        if (cardIds != null) {
            if (cardIds.isEmpty()) return emptyList()
            val placeholders = cardIds.joinToString(",") { "?" }
            conditions.add("card_id IN ($placeholders)")
            params.addAll(cardIds)
        }

        if (searchText.isNotBlank()) {
            conditions.add("(card_id LIKE ? OR name LIKE ?)")
            params.add("%$searchText%")
            params.add("%$searchText%")
        }

        if (tagFilter != null) {
            conditions.add("purpose_tags LIKE ?")
            params.add("%$tagFilter%")
        }

        val whereClause = if (conditions.isNotEmpty()) "WHERE ${conditions.joinToString(" AND ")}" else ""
        val sql = "SELECT * FROM card_purpose $whereClause ORDER BY card_id LIMIT ? OFFSET ?"
        params.add(limit)
        params.add(offset)

        return jdbcTemplate.query(sql, rowMapper, *params.toTypedArray())
    }
}
