package lin.card_purpose.db

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper

class CardPurposeRepository(private val jdbcTemplate: JdbcTemplate) {

    init {
        initSchema()
    }

    private fun initSchema() {
        // 创建卡牌字典表
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS card_catalog (
                card_id      TEXT PRIMARY KEY,
                name         TEXT NOT NULL,
                created_date TEXT
            );
            """.trimIndent()
        )
        // 创建配置表
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS card_purpose (
                card_id          TEXT PRIMARY KEY,
                purpose_tags     TEXT NOT NULL,
                replan_after_use INTEGER NOT NULL DEFAULT 0
            );
            """.trimIndent()
        )
    }

    private val rowMapper = RowMapper { rs, _ ->
        CardPurposeEntity(
            cardId = rs.getString("card_id"),
            name = rs.getString("name"),
            purposeTags = rs.getString("purpose_tags"),
            replanAfterUse = rs.getInt("replan_after_use") != 0,
            createdDate = rs.getString("created_date")
        )
    }

    fun save(entity: CardPurposeEntity) {
        val domain = entity.toDomain()
        if (domain.isDefault()) {
            jdbcTemplate.update("DELETE FROM card_purpose WHERE card_id = ?", entity.cardId)
        } else {
            val sql = """
                INSERT INTO card_purpose (card_id, purpose_tags, replan_after_use)
                VALUES (?, ?, ?)
                ON CONFLICT(card_id) DO UPDATE SET
                    purpose_tags     = excluded.purpose_tags,
                    replan_after_use = excluded.replan_after_use
            """.trimIndent()
            jdbcTemplate.update(
                sql,
                entity.cardId,
                entity.purposeTags,
                if (entity.replanAfterUse) 1 else 0
            )
        }
    }

    fun findAll(): List<CardPurposeEntity> {
        val sql = """
            SELECT c.card_id, c.name, 
                   p.purpose_tags, 
                   p.replan_after_use, 
                   c.created_date
            FROM card_purpose p
            JOIN card_catalog c ON p.card_id = c.card_id
        """.trimIndent()
        return jdbcTemplate.query(sql, rowMapper)
    }

    fun findByCardId(cardId: String): CardPurposeEntity? {
        val sql = """
            SELECT c.card_id, c.name, 
                   COALESCE(p.purpose_tags, '') as purpose_tags, 
                   COALESCE(p.replan_after_use, 0) as replan_after_use, 
                   c.created_date
            FROM card_catalog c
            LEFT JOIN card_purpose p ON c.card_id = p.card_id
            WHERE c.card_id = ?
        """.trimIndent()
        return jdbcTemplate.query(sql, rowMapper, cardId).firstOrNull()
    }

    fun findByCardIds(cardIds: Set<String>): List<CardPurposeEntity> {
        if (cardIds.isEmpty()) return emptyList()
        val placeholders = cardIds.joinToString(",") { "?" }
        val sql = """
            SELECT c.card_id, c.name, 
                   COALESCE(p.purpose_tags, '') as purpose_tags, 
                   COALESCE(p.replan_after_use, 0) as replan_after_use, 
                   c.created_date
            FROM card_catalog c
            LEFT JOIN card_purpose p ON c.card_id = p.card_id
            WHERE c.card_id IN ($placeholders)
        """.trimIndent()
        return jdbcTemplate.query(sql, rowMapper, *cardIds.toTypedArray())
    }

    fun deleteByCardId(cardId: String) {
        val sql = "DELETE FROM card_purpose WHERE card_id = ?"
        jdbcTemplate.update(sql, cardId)
    }

    fun syncCards(cards: List<CardPurposeEntity>) {
        if (cards.isEmpty()) return
        val sql = """
            INSERT INTO card_catalog (card_id, name, created_date)
            VALUES (?, ?, ?)
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
                        ps.setString(3, card.createdDate)
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
        val (defaults, customs) = entities.partition { it.toDomain().isDefault() }

        jdbcTemplate.execute("BEGIN TRANSACTION;")
        try {
            if (defaults.isNotEmpty()) {
                val deleteSql = "DELETE FROM card_purpose WHERE card_id = ?"
                jdbcTemplate.batchUpdate(
                    deleteSql,
                    object : org.springframework.jdbc.core.BatchPreparedStatementSetter {
                        override fun setValues(ps: java.sql.PreparedStatement, i: Int) {
                            ps.setString(1, defaults[i].cardId)
                        }

                        override fun getBatchSize() = defaults.size
                    }
                )
            }
            if (customs.isNotEmpty()) {
                val insertSql = """
                    INSERT INTO card_purpose (card_id, purpose_tags, replan_after_use)
                    VALUES (?, ?, ?)
                    ON CONFLICT(card_id) DO UPDATE SET
                        purpose_tags     = excluded.purpose_tags,
                        replan_after_use = excluded.replan_after_use
                """.trimIndent()
                jdbcTemplate.batchUpdate(
                    insertSql,
                    object : org.springframework.jdbc.core.BatchPreparedStatementSetter {
                        override fun setValues(ps: java.sql.PreparedStatement, i: Int) {
                            val entity = customs[i]
                            ps.setString(1, entity.cardId)
                            ps.setString(2, entity.purposeTags)
                            ps.setInt(3, if (entity.replanAfterUse) 1 else 0)
                        }

                        override fun getBatchSize() = customs.size
                    }
                )
            }
            jdbcTemplate.execute("COMMIT;")
        } catch (e: Exception) {
            jdbcTemplate.execute("ROLLBACK;")
            throw e
        }
    }

    private fun buildConditions(
        cardIds: Set<String>?,
        searchText: String,
        tagFilter: String?,
        dateFilter: String?
    ): Pair<String, List<Any>> {
        val conditions = mutableListOf<String>()
        val params = mutableListOf<Any>()

        if (cardIds != null) {
            if (cardIds.isEmpty()) return "" to emptyList()
            val placeholders = cardIds.joinToString(",") { "?" }
            conditions.add("c.card_id IN ($placeholders)")
            params.addAll(cardIds)
        }

        if (searchText.isNotBlank()) {
            conditions.add("(c.card_id LIKE ? OR c.name LIKE ?)")
            params.add("%$searchText%")
            params.add("%$searchText%")
        }

        if (tagFilter != null) {
            if (tagFilter == "未配置用途") {
                conditions.add("(p.purpose_tags IS NULL OR p.purpose_tags = '')")
            } else {
                conditions.add("p.purpose_tags LIKE ?")
                params.add("%$tagFilter%")
            }
        }

        if (dateFilter != null) {
            conditions.add("c.created_date = ?")
            params.add(dateFilter)
        }

        val whereClause = if (conditions.isNotEmpty()) "WHERE ${conditions.joinToString(" AND ")}" else ""
        return whereClause to params
    }

    fun count(cardIds: Set<String>?, searchText: String, tagFilter: String?, dateFilter: String?): Int {
        val (whereClause, params) = buildConditions(cardIds, searchText, tagFilter, dateFilter)
        if (whereClause.isEmpty() && cardIds?.isEmpty() == true) return 0
        val sql = """
            SELECT COUNT(*) 
            FROM card_catalog c
            LEFT JOIN card_purpose p ON c.card_id = p.card_id
            $whereClause
        """.trimIndent()
        return jdbcTemplate.queryForObject(sql, Int::class.java, *params.toTypedArray()) ?: 0
    }

    fun findPaginated(
        cardIds: Set<String>?,
        searchText: String,
        tagFilter: String?,
        dateFilter: String?,
        limit: Int,
        offset: Int
    ): List<CardPurposeEntity> {
        val (whereClause, params) = buildConditions(cardIds, searchText, tagFilter, dateFilter)
        if (whereClause.isEmpty() && cardIds?.isEmpty() == true) return emptyList()
        val sql = """
            SELECT c.card_id, c.name, 
                   COALESCE(p.purpose_tags, '') as purpose_tags, 
                   COALESCE(p.replan_after_use, 0) as replan_after_use, 
                   c.created_date
            FROM card_catalog c
            LEFT JOIN card_purpose p ON c.card_id = p.card_id
            $whereClause 
            ORDER BY c.created_date DESC, c.card_id ASC 
            LIMIT ? OFFSET ?
        """.trimIndent()
        val finalParams = params + limit + offset
        return jdbcTemplate.query(sql, rowMapper, *finalParams.toTypedArray())
    }

    fun getAvailableDates(): List<String> {
        val sql =
            "SELECT DISTINCT created_date FROM card_catalog WHERE created_date IS NOT NULL ORDER BY created_date DESC"
        return jdbcTemplate.query(sql) { rs, _ -> rs.getString("created_date") }.filterNotNull()
    }
}
