package lin.db

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.util.*

data class TemplateGroupEntity(
    val id: String,
    val name: String,
    val description: String? = null
)

class TemplateGroupRepository(private val jdbcTemplate: JdbcTemplate) {
    init {
        initSchema()
    }

    private fun initSchema() {
        val sql = """
            CREATE TABLE IF NOT EXISTS template_group (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL UNIQUE,
                description TEXT
            );
        """.trimIndent()
        jdbcTemplate.execute(sql)
    }

    fun save(entity: TemplateGroupEntity): String {
        val id = entity.id.ifBlank { UUID.randomUUID().toString().substring(0, 8) }
        val sql = """
            INSERT INTO template_group (id, name, description)
            VALUES (?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                name = excluded.name,
                description = excluded.description
        """.trimIndent()
        jdbcTemplate.update(sql, id, entity.name, entity.description)
        return id
    }

    fun findAll(): List<TemplateGroupEntity> {
        val sql = "SELECT * FROM template_group ORDER BY name COLLATE NOCASE ASC"
        return jdbcTemplate.query(sql, rowMapper)
    }

    fun findById(id: String): TemplateGroupEntity? {
        val sql = "SELECT * FROM template_group WHERE id = ?"
        return jdbcTemplate.query(sql, rowMapper, id).firstOrNull()
    }

    fun deleteById(id: String) {
        val sql = "DELETE FROM template_group WHERE id = ?"
        jdbcTemplate.update(sql, id)
    }

    private val rowMapper = RowMapper { rs, _ ->
        TemplateGroupEntity(
            id = rs.getString("id"),
            name = rs.getString("name"),
            description = rs.getString("description")
        )
    }
}
