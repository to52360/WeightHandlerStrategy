package lin.combo_plan.db

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper

class ComboPlanDefinitionRepository(private val jdbcTemplate: JdbcTemplate) {

    init {
        initSchema()
    }

    private fun initSchema() {
        val sql = """
            CREATE TABLE IF NOT EXISTS combo_plan_definition (
                id TEXT PRIMARY KEY,
                core_group_ids TEXT NOT NULL,
                dep_group_ids TEXT NOT NULL,
                score REAL NOT NULL,
                core_mutex INTEGER NOT NULL DEFAULT 1,
                relation TEXT NOT NULL,
                must_adjacent INTEGER NOT NULL DEFAULT 0
            );
        """.trimIndent()
        jdbcTemplate.execute(sql)
    }

    private val rowMapper = RowMapper { rs, _ ->
        ComboPlanDefinitionEntity(
            id = rs.getString("id"),
            coreGroupIds = rs.getString("core_group_ids"),
            depGroupIds = rs.getString("dep_group_ids"),
            score = rs.getDouble("score"),
            coreMutex = rs.getInt("core_mutex") == 1,
            relation = rs.getString("relation"),
            mustAdjacent = rs.getInt("must_adjacent") == 1
        )
    }

    fun save(entity: ComboPlanDefinitionEntity) {
        val sql = """
            INSERT INTO combo_plan_definition (id, core_group_ids, dep_group_ids, score, core_mutex, relation, must_adjacent)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                core_group_ids = excluded.core_group_ids,
                dep_group_ids  = excluded.dep_group_ids,
                score          = excluded.score,
                core_mutex     = excluded.core_mutex,
                relation       = excluded.relation,
                must_adjacent  = excluded.must_adjacent
        """.trimIndent()
        jdbcTemplate.update(
            sql,
            entity.id,
            entity.coreGroupIds,
            entity.depGroupIds,
            entity.score,
            if (entity.coreMutex) 1 else 0,
            entity.relation,
            if (entity.mustAdjacent) 1 else 0
        )
    }

    fun findAll(): List<ComboPlanDefinitionEntity> {
        val sql = "SELECT * FROM combo_plan_definition"
        return jdbcTemplate.query(sql, rowMapper)
    }

    fun findById(id: String): ComboPlanDefinitionEntity? {
        val sql = "SELECT * FROM combo_plan_definition WHERE id = ?"
        return jdbcTemplate.query(sql, rowMapper, id).firstOrNull()
    }

    fun deleteById(id: String) {
        val sql = "DELETE FROM combo_plan_definition WHERE id = ?"
        jdbcTemplate.update(sql, id)
    }
}
