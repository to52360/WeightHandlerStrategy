package lin.condition_tree.repository

import lin.condition_tree.domain.ConditionTreeConfigEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper

class ConditionTreeConfigRepository(private val jdbcTemplate: JdbcTemplate) {
    init {
        initSchema()
    }

    private fun initSchema() {
        val sql = """
            CREATE TABLE IF NOT EXISTS condition_tree_config (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                config_data TEXT NOT NULL
            );
        """.trimIndent()
        jdbcTemplate.execute(sql)
    }

    fun save(entity: ConditionTreeConfigEntity) {
        val sql = """
            INSERT INTO condition_tree_config (id, name, config_data)
            VALUES (?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                name = excluded.name,
                config_data = excluded.config_data
        """.trimIndent()
        jdbcTemplate.update(sql, entity.id, entity.name, entity.configData)
    }

    fun findAll(): List<ConditionTreeConfigEntity> {
        return jdbcTemplate.query("SELECT * FROM condition_tree_config", rowMapper)
    }

    fun findById(id: String): ConditionTreeConfigEntity? {
        return jdbcTemplate.query("SELECT * FROM condition_tree_config WHERE id = ?", rowMapper, id).firstOrNull()
    }

    fun deleteById(id: String) {
        jdbcTemplate.update("DELETE FROM condition_tree_config WHERE id = ?", id)
    }

    fun findAllMeta(): List<Pair<String, String>> {
        return jdbcTemplate.query("SELECT id, name FROM condition_tree_config") { rs, _ ->
            rs.getString("id") to rs.getString("name")
        }
    }

    private val rowMapper = RowMapper { rs, _ ->
        ConditionTreeConfigEntity(
            id = rs.getString("id"),
            name = rs.getString("name"),
            configData = rs.getString("config_data")
        )
    }
}
