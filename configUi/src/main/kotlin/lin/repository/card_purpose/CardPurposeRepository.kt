package lin.repository.card_purpose

import lin.bean.usePlan.CandidatePolicy
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper

class CardPurposeRepository(private val jdbcTemplate: JdbcTemplate) {

    init {
        initSchema()
    }

    private fun initSchema() {
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS card_purpose (
                card_id          TEXT PRIMARY KEY,
                purpose_tags     TEXT NOT NULL,
                replan_after_use INTEGER NOT NULL DEFAULT 0,
                candidate_policy TEXT,
                created_date     TEXT
            );
            """.trimIndent()
        )
        // 表结构改动不编码进 repository 运行时 ALTER（见 sqlite-schema-migration skill）：
        // 旧库迁移走独立脚本 docs/sql/migrations/*.sql，此处只保证新库建表结构正确。
    }

    private val rowMapper = RowMapper { rs, _ ->
        CardPurposeEntity(
            cardId = rs.getString("card_id"),
            name = rs.getString("name"),
            purposeTags = rs.getString("purpose_tags"),
            replanAfterUse = rs.getInt("replan_after_use") != 0,
            candidatePolicy = rs.getString("candidate_policy")
                ?.let { runCatching { CandidatePolicy.valueOf(it) }.getOrNull() },
            createdDate = rs.getString("created_date")
        )
    }

    fun save(entity: CardPurposeEntity) {
        val domain = entity.toDomain()
        if (domain.isDefault()) {
            jdbcTemplate.update("DELETE FROM card_purpose WHERE card_id = ?", entity.cardId)
        } else {
            val sql = """
                INSERT INTO card_purpose (card_id, purpose_tags, replan_after_use, candidate_policy, created_date)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(card_id) DO UPDATE SET
                    purpose_tags     = excluded.purpose_tags,
                    replan_after_use = excluded.replan_after_use,
                    candidate_policy = excluded.candidate_policy,
                    created_date     = excluded.created_date
            """.trimIndent()
            jdbcTemplate.update(
                sql,
                entity.cardId,
                entity.purposeTags,
                if (entity.replanAfterUse) 1 else 0,
                entity.candidatePolicy?.name,
                entity.createdDate
            )
        }
    }

    fun findAll(): List<CardPurposeEntity> {
        val sql = """
            SELECT p.card_id, h.name,
                   p.purpose_tags,
                   p.replan_after_use,
                   p.candidate_policy,
                   p.created_date
            FROM card_purpose p
            LEFT JOIN hs.cards h ON p.card_id = h.cardId
        """.trimIndent()
        return jdbcTemplate.query(sql, rowMapper)
    }

    fun findByCardId(cardId: String): CardPurposeEntity? {
        val sql = """
            SELECT h.cardId as card_id, h.name,
                   COALESCE(p.purpose_tags, '') as purpose_tags,
                   COALESCE(p.replan_after_use, 0) as replan_after_use,
                   p.candidate_policy,
                   p.created_date
            FROM hs.cards h
            LEFT JOIN card_purpose p ON h.cardId = p.card_id
            WHERE h.cardId = ?
        """.trimIndent()
        return jdbcTemplate.query(sql, rowMapper, cardId).firstOrNull()
    }

    fun findByCardIds(cardIds: Set<String>): List<CardPurposeEntity> {
        if (cardIds.isEmpty()) return emptyList()
        val placeholders = cardIds.joinToString(",") { "?" }
        val sql = """
            SELECT p.card_id, h.name,
                   p.purpose_tags,
                   p.replan_after_use,
                   p.candidate_policy,
                   p.created_date
            FROM card_purpose p
            LEFT JOIN hs.cards h ON p.card_id = h.cardId
            WHERE p.card_id IN ($placeholders)
        """.trimIndent()
        return jdbcTemplate.query(sql, rowMapper, *cardIds.toTypedArray())
    }

    fun deleteByCardId(cardId: String) {
        val sql = "DELETE FROM card_purpose WHERE card_id = ?"
        jdbcTemplate.update(sql, cardId)
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
                    INSERT INTO card_purpose (card_id, purpose_tags, replan_after_use, candidate_policy, created_date)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT(card_id) DO UPDATE SET
                        purpose_tags     = excluded.purpose_tags,
                        replan_after_use = excluded.replan_after_use,
                        candidate_policy = excluded.candidate_policy,
                        created_date     = excluded.created_date
                """.trimIndent()
                jdbcTemplate.batchUpdate(
                    insertSql,
                    object : org.springframework.jdbc.core.BatchPreparedStatementSetter {
                        override fun setValues(ps: java.sql.PreparedStatement, i: Int) {
                            val entity = customs[i]
                            ps.setString(1, entity.cardId)
                            ps.setString(2, entity.purposeTags)
                            ps.setInt(3, if (entity.replanAfterUse) 1 else 0)
                            ps.setString(4, entity.candidatePolicy?.name)
                            ps.setString(5, entity.createdDate)
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

    // ============================================================
    // 配置模式：cardIds 非空时使用，主表 hs.cards，展示卡组所有卡
    // ============================================================

    private fun buildConfigConditions(
        cardIds: Set<String>,
        searchText: String,
        tagFilter: String?,
        dateFilter: String?
    ): Pair<String, List<Any>> {
        val conditions = mutableListOf<String>()
        val params = mutableListOf<Any>()

        val placeholders = cardIds.joinToString(",") { "?" }
        conditions.add("h.cardId IN ($placeholders)")
        params.addAll(cardIds)

        if (searchText.isNotBlank()) {
            conditions.add("(h.cardId LIKE ? OR h.name LIKE ?)")
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
            conditions.add("p.created_date = ?")
            params.add(dateFilter)
        }

        val whereClause = if (conditions.isNotEmpty()) "WHERE ${conditions.joinToString(" AND ")}" else ""
        return whereClause to params
    }

    fun countConfig(cardIds: Set<String>, searchText: String, tagFilter: String?, dateFilter: String?): Int {
        if (cardIds.isEmpty()) return 0
        val (whereClause, params) = buildConfigConditions(cardIds, searchText, tagFilter, dateFilter)
        val sql = """
            SELECT COUNT(*)
            FROM hs.cards h
            LEFT JOIN card_purpose p ON h.cardId = p.card_id
            $whereClause
        """.trimIndent()
        return jdbcTemplate.queryForObject(sql, Int::class.java, *params.toTypedArray()) ?: 0
    }

    fun findConfigPage(
        cardIds: Set<String>,
        searchText: String,
        tagFilter: String?,
        dateFilter: String?,
        limit: Int,
        offset: Int
    ): List<CardPurposeEntity> {
        if (cardIds.isEmpty()) return emptyList()
        val (whereClause, params) = buildConfigConditions(cardIds, searchText, tagFilter, dateFilter)
        val sql = """
            SELECT h.cardId as card_id, h.name,
                   COALESCE(p.purpose_tags, '') as purpose_tags,
                   COALESCE(p.replan_after_use, 0) as replan_after_use,
                   p.candidate_policy,
                   p.created_date
            FROM hs.cards h
            LEFT JOIN card_purpose p ON h.cardId = p.card_id
            $whereClause
            ORDER BY p.created_date DESC, h.cardId ASC
            LIMIT ? OFFSET ?
        """.trimIndent()
        val finalParams = params + limit + offset
        return jdbcTemplate.query(sql, rowMapper, *finalParams.toTypedArray())
    }

    // ============================================================
    // 视图模式：cardIds 为空时使用，主表 card_purpose，展示已配置卡
    // ============================================================

    private fun buildViewConditions(
        searchText: String,
        tagFilter: String?,
        dateFilter: String?
    ): Pair<String, List<Any>> {
        val conditions = mutableListOf<String>()
        val params = mutableListOf<Any>()

        if (searchText.isNotBlank()) {
            conditions.add("(p.card_id LIKE ? OR h.name LIKE ?)")
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
            conditions.add("p.created_date = ?")
            params.add(dateFilter)
        }

        val whereClause = if (conditions.isNotEmpty()) "WHERE ${conditions.joinToString(" AND ")}" else ""
        return whereClause to params
    }

    fun countView(searchText: String, tagFilter: String?, dateFilter: String?): Int {
        val (whereClause, params) = buildViewConditions(searchText, tagFilter, dateFilter)
        val sql = """
            SELECT COUNT(*)
            FROM card_purpose p
            LEFT JOIN hs.cards h ON p.card_id = h.cardId
            $whereClause
        """.trimIndent()
        return jdbcTemplate.queryForObject(sql, Int::class.java, *params.toTypedArray()) ?: 0
    }

    fun findViewPage(
        searchText: String,
        tagFilter: String?,
        dateFilter: String?,
        limit: Int,
        offset: Int
    ): List<CardPurposeEntity> {
        val (whereClause, params) = buildViewConditions(searchText, tagFilter, dateFilter)
        val sql = """
            SELECT p.card_id, h.name,
                   p.purpose_tags,
                   p.replan_after_use,
                   p.candidate_policy,
                   p.created_date
            FROM card_purpose p
            LEFT JOIN hs.cards h ON p.card_id = h.cardId
            $whereClause
            ORDER BY p.created_date DESC, p.card_id ASC
            LIMIT ? OFFSET ?
        """.trimIndent()
        val finalParams = params + limit + offset
        return jdbcTemplate.query(sql, rowMapper, *finalParams.toTypedArray())
    }

    fun getAvailableDates(): List<String> {
        val sql =
            "SELECT DISTINCT created_date FROM card_purpose WHERE created_date IS NOT NULL ORDER BY created_date DESC"
        return jdbcTemplate.query(sql) { rs, _ -> rs.getString("created_date") }.filterNotNull()
    }
}
