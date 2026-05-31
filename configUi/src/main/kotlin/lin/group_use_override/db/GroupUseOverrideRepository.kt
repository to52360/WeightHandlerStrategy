package lin.group_use_override.db

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper

class GroupUseOverrideRepository(private val jdbcTemplate: JdbcTemplate) {

    init {
        initSchema()
    }

    private fun initSchema() {
        // replan_after_use 为 INTEGER NOT NULL 时用 0/1 表示 boolean，
        // 为 NULL 时表示"不覆盖"，所以用 TEXT 来存 NULL 语义。
        val sql = """
            CREATE TABLE IF NOT EXISTS group_use_override (
                card_group_id TEXT PRIMARY KEY,
                stage_override TEXT,
                replan_after_use INTEGER,
                order_weight REAL NOT NULL DEFAULT 0
            );
        """.trimIndent()
        jdbcTemplate.execute(sql)
    }

    private val rowMapper = RowMapper { rs, _ ->
        val replanRaw = rs.getObject("replan_after_use") as? Int
        GroupUseOverrideEntity(
            cardGroupId = rs.getString("card_group_id"),
            stageOverride = rs.getString("stage_override"),
            replanAfterUse = if (replanRaw == null) null else replanRaw != 0,
            orderWeight = rs.getDouble("order_weight")
        )
    }

    fun save(entity: GroupUseOverrideEntity) {
        val sql = """
            INSERT INTO group_use_override (card_group_id, stage_override, replan_after_use, order_weight)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(card_group_id) DO UPDATE SET
                stage_override = excluded.stage_override,
                replan_after_use = excluded.replan_after_use,
                order_weight = excluded.order_weight
        """.trimIndent()
        jdbcTemplate.update(
            sql,
            entity.cardGroupId,
            entity.stageOverride,
            entity.replanAfterUse?.let { if (it) 1 else 0 },
            entity.orderWeight
        )
    }

    fun findAll(): List<GroupUseOverrideEntity> {
        val sql = "SELECT * FROM group_use_override"
        return jdbcTemplate.query(sql, rowMapper)
    }

    fun findByCardGroupId(cardGroupId: String): GroupUseOverrideEntity? {
        val sql = "SELECT * FROM group_use_override WHERE card_group_id = ?"
        return jdbcTemplate.query(sql, rowMapper, cardGroupId).firstOrNull()
    }

    fun findByCardGroupIds(groupIds: Set<String>): List<GroupUseOverrideEntity> {
        if (groupIds.isEmpty()) return emptyList()
        val placeholders = groupIds.joinToString(",") { "?" }
        val sql = "SELECT * FROM group_use_override WHERE card_group_id IN ($placeholders)"
        return jdbcTemplate.query(sql, rowMapper, *groupIds.toTypedArray())
    }

    fun deleteByCardGroupId(cardGroupId: String) {
        val sql = "DELETE FROM group_use_override WHERE card_group_id = ?"
        jdbcTemplate.update(sql, cardGroupId)
    }
}
