package lin.tree_config.db

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper

class TreeConfigRepository(private val jdbcTemplate: JdbcTemplate) {
    init {
        initSchema()
    }

    private fun initSchema() {
        val sql = """
            CREATE TABLE IF NOT EXISTS tree_config (
                id TEXT PRIMARY KEY,
                group_id TEXT NOT NULL,
                name TEXT NOT NULL,
                config_data TEXT NOT NULL
            );
        """.trimIndent()
        jdbcTemplate.execute(sql)
    }

    fun save(entity: TreeConfigEntity) {
        val sql = """
            INSERT INTO tree_config (id, group_id, name, config_data) 
            VALUES (?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET 
                group_id = excluded.group_id,
                name = excluded.name,
                config_data = excluded.config_data
        """.trimIndent()
        jdbcTemplate.update(sql, entity.id, entity.groupIds, entity.name, entity.configData)
    }

    private val rowMapper = RowMapper { rs, _ ->
        TreeConfigEntity(
            id = rs.getString("id"),
            groupIds = rs.getString("group_id"),
            name = rs.getString("name"),
            configData = rs.getString("config_data")
        )
    }

    fun findAll(): List<TreeConfigEntity> {
        val sql = "SELECT * FROM tree_config"
        return jdbcTemplate.query(sql, rowMapper)
    }

    fun findById(id: String): TreeConfigEntity? {
        val sql = "SELECT * FROM tree_config WHERE id = ?"
        return jdbcTemplate.query(sql, rowMapper, id).firstOrNull()
    }

    fun deleteById(id: String) {
        val sql = "DELETE FROM tree_config WHERE id = ?"
        jdbcTemplate.update(sql, id)
    }
}
