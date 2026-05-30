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
                stage_override TEXT,
                replan_after_use INTEGER NOT NULL DEFAULT 0,
                order_weight REAL NOT NULL DEFAULT 0
            );
        """.trimIndent()
        jdbcTemplate.execute(sql)
        migrateLegacyTagsColumn()
    }

    private val rowMapper = RowMapper { rs, _ ->
        CardUseConfigEntity(
            cardGroupId = rs.getString("card_group_id"),
            purposeTags = rs.getString("purpose_tags"),
            stageOverride = rs.getString("stage_override"),
            replanAfterUse = rs.getInt("replan_after_use") != 0,
            orderWeight = rs.getDouble("order_weight")
        )
    }

    fun save(entity: CardUseConfigEntity) {
        val sql = """
            INSERT INTO card_use_config (card_group_id, purpose_tags, stage_override, replan_after_use, order_weight)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(card_group_id) DO UPDATE SET
                purpose_tags   = excluded.purpose_tags,
                stage_override = excluded.stage_override,
                replan_after_use = excluded.replan_after_use,
                order_weight = excluded.order_weight
        """.trimIndent()
        jdbcTemplate.update(
            sql,
            entity.cardGroupId,
            entity.purposeTags,
            entity.stageOverride,
            if (entity.replanAfterUse) 1 else 0,
            entity.orderWeight
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

    private fun migrateLegacyTagsColumn() {
        val columns = jdbcTemplate.queryForList("PRAGMA table_info(card_use_config)")
            .mapNotNull { it["name"] as? String }
            .toSet()
        if ("tags" !in columns) {
            if ("replan_after_use" !in columns) {
                jdbcTemplate.execute("ALTER TABLE card_use_config ADD COLUMN replan_after_use INTEGER NOT NULL DEFAULT 0")
            }
            if ("order_weight" !in columns) {
                jdbcTemplate.execute("ALTER TABLE card_use_config ADD COLUMN order_weight REAL NOT NULL DEFAULT 0")
            }
            return
        }

        jdbcTemplate.execute("DROP TABLE IF EXISTS card_use_config_new")
        jdbcTemplate.execute(
            """
                CREATE TABLE card_use_config_new (
                    card_group_id TEXT PRIMARY KEY,
                    purpose_tags TEXT NOT NULL,
                    stage_override TEXT,
                    replan_after_use INTEGER NOT NULL DEFAULT 0,
                    order_weight REAL NOT NULL DEFAULT 0
                );
            """.trimIndent()
        )
        jdbcTemplate.execute(
            """
                INSERT OR REPLACE INTO card_use_config_new (
                    card_group_id,
                    purpose_tags,
                    stage_override,
                    replan_after_use,
                    order_weight
                )
                SELECT
                    card_group_id,
                    purpose_tags,
                    stage_override,
                    CASE
                        WHEN instr(tags, 'REPLAN_AFTER_USE') > 0 OR instr(tags, 'DRAW') > 0 THEN 1
                        ELSE 0
                    END,
                    0
                FROM card_use_config;
            """.trimIndent()
        )
        jdbcTemplate.execute("DROP TABLE card_use_config")
        jdbcTemplate.execute("ALTER TABLE card_use_config_new RENAME TO card_use_config")
    }
}
