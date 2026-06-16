package lin.orthogonal_template.db

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.util.*

data class OrthogonalTemplateEntity(
    val id: String,
    val name: String,
    val description: String?,
    val type: String,          // "CONDITION" or "RULE"
    val contentJson: String
)

class OrthogonalTemplateRepository(private val jdbcTemplate: JdbcTemplate) {
    init {
        initSchema()
    }

    private fun initSchema() {
        val sql = """
            CREATE TABLE IF NOT EXISTS orthogonal_templates (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                description TEXT,
                type TEXT NOT NULL,
                content_json TEXT NOT NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            );
        """.trimIndent()
        jdbcTemplate.execute(sql)
    }

    fun save(entity: OrthogonalTemplateEntity) {
        val sql = """
            INSERT INTO orthogonal_templates (id, name, description, type, content_json)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                name = excluded.name,
                description = excluded.description,
                type = excluded.type,
                content_json = excluded.content_json
        """.trimIndent()
        jdbcTemplate.update(
            sql,
            entity.id.ifBlank { UUID.randomUUID().toString().substring(0, 8) },
            entity.name,
            entity.description,
            entity.type,
            entity.contentJson
        )
    }

    fun findAllByType(type: String): List<OrthogonalTemplateEntity> {
        val sql = "SELECT * FROM orthogonal_templates WHERE type = ? ORDER BY name COLLATE NOCASE ASC"
        return jdbcTemplate.query(sql, rowMapper, type)
    }

    fun deleteById(id: String) {
        val sql = "DELETE FROM orthogonal_templates WHERE id = ?"
        jdbcTemplate.update(sql, id)
    }

    private val rowMapper = RowMapper { rs, _ ->
        OrthogonalTemplateEntity(
            id = rs.getString("id"),
            name = rs.getString("name"),
            description = rs.getString("description"),
            type = rs.getString("type"),
            contentJson = rs.getString("content_json")
        )
    }
}
