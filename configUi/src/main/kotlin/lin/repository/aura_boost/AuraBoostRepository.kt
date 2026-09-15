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
                manager_id TEXT,
                -- T-SR-012（open-questions Q-OQ-002）：启用开关。0 = 留库但不进引擎（临时停用）。
                -- 迁移脚本见 docs/sql/migrations/2026-09-09_t-sr-012_add_aura_boost_enabled.sql
                -- （CREATE TABLE IF NOT EXISTS 不迁移旧表，旧库必须跑脚本）
                enabled INTEGER NOT NULL DEFAULT 1
            );
        """.trimIndent()
        jdbcTemplate.execute(sql)
    }

    fun save(entity: AuraBoostEntity) {
        val sql = """
            INSERT INTO aura_boost (id, name, condition_id, target_condition_id, score, manager_id, enabled)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                name = excluded.name,
                condition_id = excluded.condition_id,
                target_condition_id = excluded.target_condition_id,
                score = excluded.score,
                manager_id = excluded.manager_id,
                enabled = excluded.enabled
        """.trimIndent()
        jdbcTemplate.update(
            sql,
            entity.id, entity.name, entity.conditionId, entity.targetConditionId, entity.score, entity.managerId,
            if (entity.enabled) 1 else 0
        )
    }

    fun findAll(): List<AuraBoostEntity> =
        jdbcTemplate.query("SELECT * FROM aura_boost", rowMapper)

    fun findById(id: String): AuraBoostEntity? =
        jdbcTemplate.query("SELECT * FROM aura_boost WHERE id = ?", rowMapper, id).firstOrNull()

    fun deleteById(id: String) {
        jdbcTemplate.update("DELETE FROM aura_boost WHERE id = ?", id)
    }

    /** 归属卡组名下的全部规则（K-TG-014：卡组从属资源采集用）。 */
    fun findByManager(managerId: String): List<AuraBoostEntity> =
        jdbcTemplate.query("SELECT * FROM aura_boost WHERE manager_id = ?", rowMapper, managerId)

    /** 整类删除（K-TG-014：级联删卡组用；不做引用校验）。 */
    fun deleteByManager(managerId: String) {
        jdbcTemplate.update("DELETE FROM aura_boost WHERE manager_id = ?", managerId)
    }

    private val rowMapper = RowMapper { rs, _ ->
        AuraBoostEntity(
            id = rs.getString("id"),
            name = rs.getString("name"),
            conditionId = rs.getString("condition_id"),
            targetConditionId = rs.getString("target_condition_id"),
            score = rs.getDouble("score"),
            managerId = rs.getString("manager_id"),
            // 布尔列统一 getInt（列 NOT NULL DEFAULT 1，未迁移的旧库会在此抛错 → 提示跑迁移脚本）
            enabled = rs.getInt("enabled") == 1
        )
    }
}
