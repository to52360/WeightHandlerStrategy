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
                bindings_summary TEXT NOT NULL,
                name TEXT NOT NULL,
                config_data TEXT NOT NULL
            );
        """.trimIndent()
        jdbcTemplate.execute(sql)

        // 兼容旧表：尝试重命名 group_id 列
        try {
            jdbcTemplate.execute("ALTER TABLE tree_config RENAME COLUMN group_id TO bindings_summary;")
        } catch (e: Exception) {
            // 列已重命名或不存在，忽略
        }
    }

    fun save(entity: TreeConfigEntity) {
        val sql = """
            INSERT INTO tree_config (id, binding_type, bindings_summary, name, config_data, enabled) 
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET 
                binding_type = excluded.binding_type,
                bindings_summary = excluded.bindings_summary,
                name = excluded.name,
                config_data = excluded.config_data,
                enabled = excluded.enabled
        """.trimIndent()
        jdbcTemplate.update(
            sql,
            entity.id,
            entity.bindingType,
            entity.bindingsSummary,
            entity.name,
            entity.configData,
            if (entity.enabled) 1 else 0
        )
    }

    private val rowMapper = RowMapper { rs, _ ->
        TreeConfigEntity(
            id = rs.getString("id"),
            bindingType = rs.getString("binding_type") ?: "UNKNOWN",
            bindingsSummary = rs.getString("bindings_summary"),
            name = rs.getString("name"),
            configData = rs.getString("config_data"),
            enabled = rs.getInt("enabled") != 0
        )
    }

    fun findAll(): List<TreeConfigEntity> {
        val sql = "SELECT * FROM tree_config ORDER BY name COLLATE NOCASE ASC"
        return jdbcTemplate.query(sql, rowMapper)
    }

    fun countAll(bindingType: String? = null): Int {
        var sql = "SELECT COUNT(*) FROM tree_config"
        val params = mutableListOf<Any>()
        if (bindingType != null) {
            sql += " WHERE binding_type = ?"
            params.add(bindingType)
        }
        return jdbcTemplate.queryForObject(sql, Int::class.java, *params.toTypedArray()) ?: 0
    }

    fun findPage(offset: Int, limit: Int, bindingType: String? = null): List<TreeConfigEntity> {
        var sql = "SELECT * FROM tree_config"
        val params = mutableListOf<Any>()
        if (bindingType != null) {
            sql += " WHERE binding_type = ?"
            params.add(bindingType)
        }
        sql += " ORDER BY name COLLATE NOCASE ASC LIMIT ? OFFSET ?"
        params.add(limit)
        params.add(offset)
        return jdbcTemplate.query(sql, rowMapper, *params.toTypedArray())
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
