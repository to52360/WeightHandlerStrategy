package lin.ui.db

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.util.*

data class OrthogonalTemplateEntity(
    val id: String,
    val name: String,
    val description: String?,
    val groupId: String? = null,    // FK → template_group.id
    val type: String,               // "CONDITION" or "RULE"
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
                group_id TEXT,
                type TEXT NOT NULL,
                content_json TEXT NOT NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (group_id) REFERENCES template_group(id)
            );
        """.trimIndent()
        jdbcTemplate.execute(sql)
    }

    fun save(entity: OrthogonalTemplateEntity) {
        val sql = """
            INSERT INTO orthogonal_templates (id, name, description, group_id, type, content_json)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                name = excluded.name,
                description = excluded.description,
                group_id = excluded.group_id,
                type = excluded.type,
                content_json = excluded.content_json
        """.trimIndent()
        jdbcTemplate.update(
            sql,
            entity.id.ifBlank { UUID.randomUUID().toString().substring(0, 8) },
            entity.name,
            entity.description,
            entity.groupId,
            entity.type,
            entity.contentJson
        )
    }

    fun findAllByType(type: String): List<OrthogonalTemplateEntity> {
        val sql = "SELECT * FROM orthogonal_templates WHERE type = ? ORDER BY name COLLATE NOCASE ASC"
        return jdbcTemplate.query(sql, rowMapper, type)
    }

    /** 某个类型下已有分组（关联查询，仅返回有模板的分组） */
    fun findUsedGroups(type: String): List<String> {
        val sql = """
            SELECT DISTINCT tg.id, tg.name FROM template_group tg
            JOIN orthogonal_templates ot ON ot.group_id = tg.id
            WHERE ot.type = ? ORDER BY tg.name COLLATE NOCASE ASC
        """.trimIndent()
        return jdbcTemplate.query(sql, { rs, _ -> rs.getString("name") }, type)
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
            groupId = rs.getString("group_id"),
            type = rs.getString("type"),
            contentJson = rs.getString("content_json")
        )
    }
}
