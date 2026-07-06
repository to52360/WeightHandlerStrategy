package lin.ui.tree_config.db

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper

class TreeConfigRepository(private val jdbcTemplate: JdbcTemplate) {
    init {
        initSchema()
    }

    private fun initSchema() {
        val treeConfigTable = """
            CREATE TABLE IF NOT EXISTS tree_config (
                id TEXT PRIMARY KEY,
                binding_type TEXT NOT NULL,
                binding_ids TEXT NOT NULL,
                name TEXT NOT NULL,
                description TEXT,
                config_data TEXT NOT NULL,
                enabled INTEGER NOT NULL DEFAULT 1,
                manager_id TEXT,
                node_names TEXT
            );
        """.trimIndent()
        jdbcTemplate.execute(treeConfigTable)

        val leafConfigTable = """
            CREATE TABLE IF NOT EXISTS evaluator_leaf_config (
                config_id TEXT NOT NULL,
                node_id TEXT NOT NULL,
                leaf_config TEXT NOT NULL DEFAULT '{}',
                PRIMARY KEY (config_id, node_id)
            );
        """.trimIndent()
        jdbcTemplate.execute(leafConfigTable)

        // 平滑数据迁移：如果有旧表 tree_config 中的 is_template 列和模板数据，自动搬迁并清理
        migrateTemplatesIfNeeded()
    }

    private fun migrateTemplatesIfNeeded() {
        try {
            val checkColumnSql = "PRAGMA table_info(tree_config)"
            val columns = jdbcTemplate.queryForList(checkColumnSql)
            val hasIsTemplate = columns.any { it["name"].toString().equals("is_template", ignoreCase = true) }
            if (hasIsTemplate) {
                val migrateSql = """
                    INSERT OR IGNORE INTO evaluator_tree_template (id, name, description, config_data)
                    SELECT id, name, description, config_data 
                    FROM tree_config 
                    WHERE is_template = 1
                """.trimIndent()
                jdbcTemplate.execute(migrateSql)

                val deleteOldTemplatesSql = "DELETE FROM tree_config WHERE is_template = 1"
                jdbcTemplate.execute(deleteOldTemplatesSql)
            }
        } catch (e: Exception) {
            // 迁移过程遇错容错，避免阻塞引擎启动
            e.printStackTrace()
        }
    }

    fun save(entity: TreeConfigEntity) {
        val sql = """
            INSERT INTO tree_config (id, binding_type, binding_ids, name, description, config_data, enabled, manager_id, node_names) 
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET 
                binding_type = excluded.binding_type,
                binding_ids = excluded.binding_ids,
                name = excluded.name,
                description = excluded.description,
                config_data = excluded.config_data,
                enabled = excluded.enabled,
                manager_id = excluded.manager_id,
                node_names = excluded.node_names
        """.trimIndent()
        jdbcTemplate.update(
            sql,
            entity.id,
            entity.bindingType,
            entity.bindingIds,
            entity.name,
            entity.description,
            entity.configData,
            if (entity.enabled) 1 else 0,
            entity.managerId,
            entity.nodeNames
        )
    }

    private val rowMapper = RowMapper { rs, _ ->
        TreeConfigEntity(
            id = rs.getString("id"),
            bindingType = rs.getString("binding_type"),
            bindingIds = rs.getString("binding_ids"),
            name = rs.getString("name"),
            description = rs.getString("description"),
            configData = rs.getString("config_data"),
            enabled = rs.getInt("enabled") != 0,
            managerId = rs.getString("manager_id"),
            nodeNames = rs.getString("node_names")
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

    /** 按 managerId 过滤，null=全局共享 */
    fun findByManagerId(managerId: String?, includeGlobal: Boolean = true): List<TreeConfigEntity> {
        if (includeGlobal && managerId != null) {
            val sql =
                "SELECT * FROM tree_config WHERE (manager_id = ? OR manager_id IS NULL) ORDER BY name COLLATE NOCASE ASC"
            return jdbcTemplate.query(sql, rowMapper, managerId)
        } else if (managerId != null) {
            val sql =
                "SELECT * FROM tree_config WHERE manager_id = ? ORDER BY name COLLATE NOCASE ASC"
            return jdbcTemplate.query(sql, rowMapper, managerId)
        } else {
            // 全局共享的
            val sql =
                "SELECT * FROM tree_config WHERE manager_id IS NULL ORDER BY name COLLATE NOCASE ASC"
            return jdbcTemplate.query(sql, rowMapper)
        }
    }
}
