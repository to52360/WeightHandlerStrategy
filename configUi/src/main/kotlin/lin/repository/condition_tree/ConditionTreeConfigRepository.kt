package lin.repository.condition_tree

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
                config_data TEXT NOT NULL,
                manager_id TEXT
            );
        """.trimIndent()
        jdbcTemplate.execute(sql)
    }

    fun save(entity: ConditionTreeConfigEntity) {
        val sql = """
            INSERT INTO condition_tree_config (id, name, config_data, manager_id)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                name = excluded.name,
                config_data = excluded.config_data,
                manager_id = excluded.manager_id
        """.trimIndent()
        jdbcTemplate.update(sql, entity.id, entity.name, entity.configData, entity.managerId)
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

    /**
     * 根据卡组 managerId 查询可用的条件树元数据：返回当前卡组私有条件树 + 全局共享条件树 (manager_id 为 NULL 或空)。
     */
    fun findMetaByManagerId(managerId: String?): List<Pair<String, String>> {
        if (managerId.isNullOrEmpty()) {
            return findAllMeta()
        }
        val sql =
            "SELECT id, name FROM condition_tree_config WHERE manager_id = ? OR manager_id IS NULL OR manager_id = ''"
        return jdbcTemplate.query(sql, { rs, _ ->
            rs.getString("id") to rs.getString("name")
        }, managerId)
    }

    private val rowMapper = RowMapper { rs, _ ->
        ConditionTreeConfigEntity(
            id = rs.getString("id"),
            name = rs.getString("name"),
            configData = rs.getString("config_data"),
            managerId = rs.getString("manager_id")
        )
    }
}
