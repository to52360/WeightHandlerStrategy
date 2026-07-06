package lin.ui.tree_config.db

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper

class EvaluatorTreeTemplateRepository(private val jdbcTemplate: JdbcTemplate) {
    init {
        initSchema()
    }

    private fun initSchema() {
        val sql = """
            CREATE TABLE IF NOT EXISTS evaluator_tree_template (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                description TEXT,
                group_id TEXT,
                config_data TEXT NOT NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                node_names TEXT,
                FOREIGN KEY (group_id) REFERENCES template_group(id)
            );
        """.trimIndent()
        jdbcTemplate.execute(sql)
    }

    private val rowMapper = RowMapper { rs, _ ->
        EvaluatorTreeTemplateEntity(
            id = rs.getString("id"),
            name = rs.getString("name"),
            description = rs.getString("description"),
            groupId = rs.getString("group_id"),
            configData = rs.getString("config_data"),
            nodeNames = rs.getString("node_names")
        )
    }

    fun save(entity: EvaluatorTreeTemplateEntity) {
        val sql = """
            INSERT INTO evaluator_tree_template (id, name, description, group_id, config_data, node_names)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                name = excluded.name,
                description = excluded.description,
                group_id = excluded.group_id,
                config_data = excluded.config_data,
                node_names = excluded.node_names
        """.trimIndent()
        jdbcTemplate.update(
            sql,
            entity.id,
            entity.name,
            entity.description,
            entity.groupId,
            entity.configData,
            entity.nodeNames
        )
    }

    fun findAll(): List<EvaluatorTreeTemplateEntity> {
        val sql = "SELECT * FROM evaluator_tree_template ORDER BY name COLLATE NOCASE ASC"
        return jdbcTemplate.query(sql, rowMapper)
    }

    fun findByGroupId(groupId: String?): List<EvaluatorTreeTemplateEntity> {
        return if (groupId == null) {
            val sql = "SELECT * FROM evaluator_tree_template WHERE group_id IS NULL ORDER BY name COLLATE NOCASE ASC"
            jdbcTemplate.query(sql, rowMapper)
        } else {
            val sql = "SELECT * FROM evaluator_tree_template WHERE group_id = ? ORDER BY name COLLATE NOCASE ASC"
            jdbcTemplate.query(sql, rowMapper, groupId)
        }
    }

    fun findById(id: String): EvaluatorTreeTemplateEntity? {
        val sql = "SELECT * FROM evaluator_tree_template WHERE id = ?"
        return jdbcTemplate.query(sql, rowMapper, id).firstOrNull()
    }

    fun deleteById(id: String) {
        val sql = "DELETE FROM evaluator_tree_template WHERE id = ?"
        jdbcTemplate.update(sql, id)
    }
}
