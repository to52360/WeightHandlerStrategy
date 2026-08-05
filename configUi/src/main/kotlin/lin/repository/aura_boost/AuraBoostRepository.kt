package lin.repository.aura_boost

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper

class AuraBoostRepository(private val jdbcTemplate: JdbcTemplate) {
    init {
        initSchema()
    }

    private fun initSchema() {
        val sql = """
            CREATE TABLE IF NOT EXISTS aura_boost (
                id TEXT PRIMARY KEY,
                name TEXT,
                condition_id TEXT NOT NULL,
                target_condition_id TEXT NOT NULL,
                score REAL NOT NULL,
                manager_id TEXT
            );
        """.trimIndent()
        jdbcTemplate.execute(sql)
    }

    fun save(entity: AuraBoostEntity) {
        val sql = """
            INSERT INTO aura_boost (id, name, condition_id, target_condition_id, score, manager_id)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                name = excluded.name,
                condition_id = excluded.condition_id,
                target_condition_id = excluded.target_condition_id,
                score = excluded.score,
                manager_id = excluded.manager_id
        """.trimIndent()
        jdbcTemplate.update(
            sql,
            entity.id, entity.name, entity.conditionId, entity.targetConditionId, entity.score, entity.managerId
        )
    }

    fun findAll(): List<AuraBoostEntity> =
        jdbcTemplate.query("SELECT * FROM aura_boost", rowMapper)

    fun findById(id: String): AuraBoostEntity? =
        jdbcTemplate.query("SELECT * FROM aura_boost WHERE id = ?", rowMapper, id).firstOrNull()

    fun deleteById(id: String) {
        jdbcTemplate.update("DELETE FROM aura_boost WHERE id = ?", id)
    }

    private val rowMapper = RowMapper { rs, _ ->
        AuraBoostEntity(
            id = rs.getString("id"),
            name = rs.getString("name"),
            conditionId = rs.getString("condition_id"),
            targetConditionId = rs.getString("target_condition_id"),
            score = rs.getDouble("score"),
            managerId = rs.getString("manager_id")
        )
    }
}
